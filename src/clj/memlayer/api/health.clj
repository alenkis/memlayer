(ns memlayer.api.health
  (:require [memlayer.version :as version]))

(defn handler [_request]
  {:status 200
   :body   {:status   "ok"
            :version  (version/version)
            :git-sha  (version/git-sha*)
            :built-at (version/built-at)}})
