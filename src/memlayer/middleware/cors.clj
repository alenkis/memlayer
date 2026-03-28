(ns memlayer.middleware.cors
  "Ring middleware for Cross-Origin Resource Sharing (CORS).")

(defn- allowed-origins-for
  "Build the allowed-origins set, substituting the dev dashboard port."
  [dashboard-port]
  #{"https://app.memlayer.dev"
    (str "http://localhost:" dashboard-port)})

(defn-   origin-allowed?
  [allowed-origins origin]
  (contains? allowed-origins origin))

(defn- cors-headers
  [origin]
  {"Access-Control-Allow-Origin"      origin
   "Access-Control-Allow-Methods"     "GET, POST, PUT, DELETE, OPTIONS"
   "Access-Control-Allow-Headers"     "Content-Type, Authorization, X-API-Key"
   "Access-Control-Allow-Credentials" "true"
   "Access-Control-Max-Age"           "86400"})

(defn wrap-cors
  "Ring middleware that adds CORS headers for allowed origins.
   Handles preflight OPTIONS requests and adds headers to all responses.

   Options:
     :allowed-origins  set of allowed origin strings (overrides default)
     :dashboard-port   port for localhost origin (default: 3000)
     :enabled?         whether CORS is enabled (default: true)"
  [handler {:keys [allowed-origins dashboard-port enabled?]
            :or   {dashboard-port 3000
                   enabled?       true}}]
  (let [origins (or allowed-origins (allowed-origins-for dashboard-port))]
    (if-not enabled?
      handler
      (fn [request]
        (let [origin (get-in request [:headers "origin"])]
          (if (and origin (origin-allowed? origins origin))
            (if (= :options (:request-method request))
              {:status  204
               :headers (cors-headers origin)
               :body    ""}
              (let [response (handler request)]
                (update response :headers merge (cors-headers origin))))
            (handler request)))))))
