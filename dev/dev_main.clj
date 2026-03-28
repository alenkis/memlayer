(ns dev-main
  "Dev entry point — starts nREPL + the local system.
   Serves API + bundled dashboard on a single port with nREPL for editor connection."
  (:require [nrepl.server :as nrepl]
            [memlayer.local :as local]
            [clojure.tools.logging :as log]))

(defn -main [& _args]
  (let [nrepl-server (nrepl/start-server
                      :port 0
                      :handler (requiring-resolve 'cider.nrepl/cider-nrepl-handler))
        port (:port nrepl-server)]
    (spit ".nrepl-port" port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (nrepl/stop-server nrepl-server)
                                 (.delete (java.io.File. ".nrepl-port")))))
    (log/info (str "nREPL (cider) server started on port " port " — nrepl://localhost:" port)))
  (local/-main))
