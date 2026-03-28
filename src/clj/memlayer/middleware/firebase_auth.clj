(ns memlayer.middleware.firebase-auth
  "Ring middleware for Firebase JWT and API token authentication.

   API routes:   X-API-Key header -> token hash lookup in datahike
   Dashboard:    Authorization: Bearer <jwt> -> Firebase JWT validation (RSA signature)

   In e2e-mode:  /admin/reset is exempt from API auth, and any Bearer token
                 is accepted without JWT signature verification.

   Attaches :user-context {:user-id, :email, :name} to the request."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [memlayer.persistence.tokens :as tokens])
  (:import [java.security MessageDigest]
           [java.security.interfaces RSAPublicKey]
           [java.util HexFormat]
           [java.net URL]
           [java.util.concurrent TimeUnit]
           [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.exceptions JWTVerificationException]
           [com.auth0.jwk JwkProviderBuilder]))

;; -- Helpers --

(defn- sha256 ^String [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (.formatHex (HexFormat/of) bytes)))

(defn- secure-eq
  "Constant-time comparison of two strings using MessageDigest.isEqual.
   Prevents timing side-channel attacks on hash comparisons."
  [^String a ^String b]
  (and a b
       (MessageDigest/isEqual (.getBytes a "UTF-8")
                              (.getBytes b "UTF-8"))))

;; -- Firebase JWT validation --
;; Uses com.auth0/java-jwt + com.auth0/jwks-rsa for full RSA signature
;; verification against Google's public keys (JWKS endpoint).

(def ^:private google-jwks-url
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")

(defonce ^:private jwk-provider
  (-> (JwkProviderBuilder. (URL. google-jwks-url))
      (.cached 10 1 TimeUnit/HOURS)
      (.rateLimited 10 1 TimeUnit/MINUTES)
      (.build)))

(defn- validate-firebase-jwt
  "Validate a Firebase JWT with full RSA signature verification.
   Fetches the matching public key from Google's JWKS endpoint (cached),
   verifies the RS256 signature, and validates issuer, audience, and expiry.
   Returns {:user-id :email :name} or nil."
  [token project-id]
  (try
    (let [decoded   (JWT/decode token)
          kid       (.getKeyId decoded)
          jwk       (.get jwk-provider kid)
          algorithm (Algorithm/RSA256 ^RSAPublicKey (.getPublicKey jwk) nil)
          verifier  (-> (JWT/require algorithm)
                        (.withIssuer (str "https://securetoken.google.com/" project-id))
                        (.withAudience (into-array String [project-id]))
                        (.build))
          verified  (.verify verifier token)
          auth-time (.asLong (.getClaim verified "auth_time"))
          now-secs  (quot (System/currentTimeMillis) 1000)]
      (if (and auth-time (<= auth-time now-secs))
        {:user-id (.getSubject verified)
         :email   (.asString (.getClaim verified "email"))
         :name    (.asString (.getClaim verified "name"))}
        (do (log/warn "Firebase JWT rejected: auth_time missing or in the future")
            nil)))
    (catch JWTVerificationException e
      (log/warn "Firebase JWT verification failed:" (.getMessage e))
      nil)
    (catch Exception e
      (log/error e "Unexpected error during Firebase JWT verification")
      nil)))

;; -- Auth strategies --

(defn- try-api-key-auth
  "Try X-API-Key authentication via token hash lookup in datahike."
  [request db]
  (when-let [api-key (get-in request [:headers "x-api-key"])]
    (let [key-hash (sha256 api-key)
          token    (tokens/find-token-by-hash db key-hash)]
      (cond
        (nil? token)
        (do (log/debug "API key not found") nil)

        (:token/revoked-at token)
        (do (log/warn "Revoked API key used") nil)

        :else
        {:user-id (:token/owner token)
         :email   nil
         :name    nil}))))

(defn- try-bearer-auth
  "Try Authorization: Bearer JWT authentication."
  [request project-id]
  (when-let [auth-header (get-in request [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (let [token (subs auth-header 7)]
        (validate-firebase-jwt token project-id)))))

;; -- Skip paths --

(def ^:private skip-paths
  #{"/health"})

(def ^:private e2e-exempt-paths
  "Paths exempt from API auth in e2e mode (test utilities)."
  #{"/api/v1/admin/reset"})

(defn- skip-auth?
  "Returns true for paths that should bypass authentication."
  [uri]
  (or (contains? skip-paths uri)
      (not (or (str/starts-with? (or uri "") "/api/")
               (str/starts-with? (or uri "") "/mcp")))))

;; -- E2E auth context --

(def ^:private e2e-user-context
  "Hardcoded user context for e2e test mode."
  {:user-id "e2e-test-user"
   :email   "e2e@test.local"
   :name    "E2E Test User"})

;; -- Middleware --

(defonce ^:private e2e-mode-warned? (atom false))

(defn wrap-dashboard-auth
  "Reitit middleware that authenticates dashboard requests.
   Accepts Firebase JWT (Bearer). In e2e-mode, accepts any Bearer token
   without signature verification.
   auth-enabled? is an internal param for unit tests (default true).
   Attaches :user-context to the request."
  [handler {:keys [_db firebase-project-id e2e-mode? auth-enabled?]
            :or   {auth-enabled? true e2e-mode? false}}]
  (when (and e2e-mode? (compare-and-set! e2e-mode-warned? false true))
    (log/warn "AUTH_E2E_MODE is enabled. Firebase JWT verification disabled."
              "Do NOT use in production."))
  (fn [request]
    (let [uri (:uri request)]
      (if (or (not auth-enabled?) (skip-auth? uri))
        (handler (assoc request :user-context {:user-id "anonymous" :email nil :name nil}))
        (if e2e-mode?
          ;; E2E mode: accept any Bearer token without JWT verification
          (let [auth-header (get-in request [:headers "authorization"])]
            (if (and auth-header (str/starts-with? auth-header "Bearer "))
              (handler (assoc request :user-context e2e-user-context))
              (do
                (log/warn "Unauthorized dashboard request to" uri "(e2e mode, no Bearer)")
                {:status  401
                 :body    {:error   "Unauthorized"
                           :message "Bearer token required"}})))
          ;; Normal mode: full Firebase JWT validation
          (if-let [user-ctx (try-bearer-auth request firebase-project-id)]
            (handler (assoc request :user-context user-ctx))
            (do
              (log/warn "Unauthorized dashboard request to" uri)
              {:status  401
               :body    {:error   "Unauthorized"
                         :message "Valid Firebase JWT required"}})))))))

(defn wrap-api-auth
  "Reitit middleware that authenticates API requests.
   Accepts X-API-Key (token hash lookup).
   Falls back to legacy api-key-hash config if no token found in DB.
   In e2e-mode, /admin/reset is exempt.
   auth-enabled? is an internal param for unit tests (default true).
   Attaches :user-context to the request."
  [handler {:keys [db legacy-api-key-hash e2e-mode? auth-enabled?]
            :or   {auth-enabled? true e2e-mode? false}}]
  (fn [request]
    (let [uri (:uri request)]
      (if (or (not auth-enabled?) (skip-auth? uri))
        (handler (assoc request :user-context {:user-id "anonymous" :email nil :name nil}))
        (if (and e2e-mode? (contains? e2e-exempt-paths uri))
          (handler (assoc request :user-context e2e-user-context))
          (let [user-ctx (or (try-api-key-auth request db)
                             ;; Legacy: check against config api-key-hash
                             (when-let [api-key (get-in request [:headers "x-api-key"])]
                               (when (and legacy-api-key-hash
                                          (secure-eq legacy-api-key-hash (sha256 api-key)))
                                 {:user-id "legacy" :email nil :name nil})))]
            (if user-ctx
              (handler (assoc request :user-context user-ctx))
              (do
                (log/warn "Unauthorized API request to" uri)
                {:status  401
                 :body    {:error   "Unauthorized"
                           :message "Valid API key required"}}))))))))
