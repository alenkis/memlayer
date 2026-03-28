(ns dev-main
  "Dev entry point — starts nREPL + the full system.
   Behaves like `make server` but with an nREPL port for editor connection."
  (:require [nrepl.server :as nrepl]
            [memlayer.system :as sys]
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
  (sys/-main))
