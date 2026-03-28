(ns memlayer.middleware.firebase-auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [memlayer.middleware.firebase-auth :as auth]
            [memlayer.persistence.tokens :as tokens]))

;; -- Test helpers --

(defn- fresh-conn
  "Create an in-memory datahike connection with token schema."
  []
  (let [cfg {:store {:backend :memory
                     :id (java.util.UUID/randomUUID)}
             :schema-flexibility :write}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (tokens/transact-schema! conn)
      conn)))

(defn- ok-handler [request]
  {:status 200 :headers {} :body (:user-context request)})

;; -- secure-eq tests --

(deftest secure-eq-equal-strings
  (testing "equal strings return true"
    (is (#'auth/secure-eq "hello" "hello"))))

(deftest secure-eq-different-strings
  (testing "different strings return false"
    (is (not (#'auth/secure-eq "hello" "world")))))

(deftest secure-eq-nil-inputs
  (testing "nil inputs return falsy"
    (is (not (#'auth/secure-eq nil "hello")))
    (is (not (#'auth/secure-eq "hello" nil)))
    (is (not (#'auth/secure-eq nil nil)))))

;; -- skip-auth? tests --

(deftest skip-auth-health-endpoint
  (testing "/health is skipped"
    (is (#'auth/skip-auth? "/health"))))

(deftest skip-auth-api-route
  (testing "/api/v1/retain is not skipped"
    (is (not (#'auth/skip-auth? "/api/v1/retain")))))

(deftest skip-auth-non-api-route
  (testing "non-API routes are skipped"
    (is (#'auth/skip-auth? "/dashboard"))
    (is (#'auth/skip-auth? "/"))))

;; -- try-api-key-auth tests --

(deftest try-api-key-auth-valid-key
  (testing "valid API key returns user context"
    (let [conn   (fresh-conn)
          result (tokens/create-token! conn "owner-123" "test-token")
          token  (:token result)
          req    {:headers {"x-api-key" token}}]
      (is (= "owner-123" (:user-id (#'auth/try-api-key-auth req conn)))))))

(deftest try-api-key-auth-unknown-key
  (testing "unknown API key returns nil"
    (let [conn (fresh-conn)
          req  {:headers {"x-api-key" "mlk_unknown"}}]
      (is (nil? (#'auth/try-api-key-auth req conn))))))

(deftest try-api-key-auth-revoked-key
  (testing "revoked API key returns nil"
    (let [conn   (fresh-conn)
          result (tokens/create-token! conn "owner-123" "test-token")]
      (tokens/revoke-token! conn (:id result) "owner-123")
      (is (nil? (#'auth/try-api-key-auth {:headers {"x-api-key" (:token result)}} conn))))))

(deftest try-api-key-auth-no-header
  (testing "missing x-api-key header returns nil"
    (let [conn (fresh-conn)]
      (is (nil? (#'auth/try-api-key-auth {:headers {}} conn))))))

;; -- wrap-api-auth middleware tests --

(deftest wrap-api-auth-valid-key
  (testing "valid API key gets 200"
    (let [conn   (fresh-conn)
          result (tokens/create-token! conn "user-1" "my-key")
          mw     (auth/wrap-api-auth ok-handler {:db conn})
          resp   (mw {:uri "/api/v1/retain" :headers {"x-api-key" (:token result)}})]
      (is (= 200 (:status resp)))
      (is (= "user-1" (:user-id (:body resp)))))))

(deftest wrap-api-auth-no-creds
  (testing "no credentials returns 401"
    (let [conn (fresh-conn)
          mw   (auth/wrap-api-auth ok-handler {:db conn})
          resp (mw {:uri "/api/v1/retain" :headers {}})]
      (is (= 401 (:status resp)))
      (is (= {:error "Unauthorized" :message "Valid API key required"} (:body resp)))
      (is (nil? (get-in resp [:headers "Content-Type"]))
          "No Content-Type header — let muuntaja handle encoding"))))

(deftest wrap-api-auth-revoked-key
  (testing "revoked API key returns 401"
    (let [conn   (fresh-conn)
          result (tokens/create-token! conn "user-1" "my-key")]
      (tokens/revoke-token! conn (:id result) "user-1")
      (let [mw   (auth/wrap-api-auth ok-handler {:db conn})
            resp (mw {:uri "/api/v1/retain" :headers {"x-api-key" (:token result)}})]
        (is (= 401 (:status resp)))))))

(deftest wrap-api-auth-legacy-hash
  (testing "legacy API key hash comparison works"
    (let [conn     (fresh-conn)
          api-key  "mlk_legacykey123"
          key-hash (#'auth/sha256 api-key)
          mw       (auth/wrap-api-auth ok-handler {:db conn
                                                   :legacy-api-key-hash key-hash})
          resp     (mw {:uri "/api/v1/retain" :headers {"x-api-key" api-key}})]
      (is (= 200 (:status resp)))
      (is (= "legacy" (:user-id (:body resp)))))))

(deftest wrap-api-auth-x-user-id-rejected
  (testing "x-user-id header is always rejected (dev mode removed)"
    (let [conn (fresh-conn)
          mw   (auth/wrap-api-auth ok-handler {:db conn})
          resp (mw {:uri "/api/v1/retain" :headers {"x-user-id" "attacker"}})]
      (is (= 401 (:status resp))))))

(deftest wrap-api-auth-auth-enabled-false
  (testing "auth-enabled? false passes through (unit test escape hatch)"
    (let [conn (fresh-conn)
          mw   (auth/wrap-api-auth ok-handler {:db conn :auth-enabled? false})
          resp (mw {:uri "/api/v1/retain" :headers {}})]
      (is (= 200 (:status resp)))
      (is (= "anonymous" (:user-id (:body resp)))))))

;; -- e2e-mode tests --

(deftest wrap-api-auth-e2e-admin-reset-exempt
  (testing "e2e mode exempts /api/v1/admin/reset"
    (let [conn (fresh-conn)
          mw   (auth/wrap-api-auth ok-handler {:db conn :e2e-mode? true})
          resp (mw {:uri "/api/v1/admin/reset" :headers {}})]
      (is (= 200 (:status resp)))
      (is (= "e2e-test-user" (:user-id (:body resp)))))))

(deftest wrap-api-auth-e2e-normal-route-requires-token
  (testing "e2e mode still requires API token for normal routes"
    (let [conn (fresh-conn)
          mw   (auth/wrap-api-auth ok-handler {:db conn :e2e-mode? true})
          resp (mw {:uri "/api/v1/retain" :headers {}})]
      (is (= 401 (:status resp))))))

(deftest wrap-api-auth-e2e-normal-route-valid-token
  (testing "e2e mode with valid API token passes"
    (let [conn   (fresh-conn)
          result (tokens/create-token! conn "user-1" "my-key")
          mw     (auth/wrap-api-auth ok-handler {:db conn :e2e-mode? true})
          resp   (mw {:uri "/api/v1/retain" :headers {"x-api-key" (:token result)}})]
      (is (= 200 (:status resp)))
      (is (= "user-1" (:user-id (:body resp)))))))

;; -- wrap-dashboard-auth middleware tests --

(deftest wrap-dashboard-auth-no-creds
  (testing "no credentials returns 401"
    (let [mw   (auth/wrap-dashboard-auth ok-handler {})
          resp (mw {:uri "/api/v1/account/me" :headers {}})]
      (is (= 401 (:status resp)))
      (is (= {:error "Unauthorized" :message "Valid Firebase JWT required"} (:body resp)))
      (is (nil? (get-in resp [:headers "Content-Type"]))
          "No Content-Type header — let muuntaja handle encoding"))))

(deftest wrap-dashboard-auth-e2e-mode-accepts-any-bearer
  (testing "e2e mode accepts any Bearer token"
    (let [mw   (auth/wrap-dashboard-auth ok-handler {:e2e-mode? true})
          resp (mw {:uri "/api/v1/account/me" :headers {"authorization" "Bearer fake-id-token"}})]
      (is (= 200 (:status resp)))
      (is (= "e2e-test-user" (:user-id (:body resp)))))))

(deftest wrap-dashboard-auth-e2e-mode-no-bearer-401
  (testing "e2e mode without Bearer token returns 401"
    (let [mw   (auth/wrap-dashboard-auth ok-handler {:e2e-mode? true})
          resp (mw {:uri "/api/v1/account/me" :headers {}})]
      (is (= 401 (:status resp))))))

(deftest wrap-dashboard-auth-auth-enabled-false
  (testing "auth-enabled? false passes through (unit test escape hatch)"
    (let [mw   (auth/wrap-dashboard-auth ok-handler {:auth-enabled? false})
          resp (mw {:uri "/api/v1/account/me" :headers {}})]
      (is (= 200 (:status resp)))
      (is (= "anonymous" (:user-id (:body resp)))))))
