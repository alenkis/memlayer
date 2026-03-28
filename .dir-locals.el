((nil
  (cider-inject-dependencies-at-jack-in . t)
  (cider-default-cljs-repl . shadow)
  (cider-shadow-default-options . ":dashboard")
  (cider-shadow-cljs-parameters . "watch :dashboard"))
 (clojure-mode
  (cider-preferred-build-tool . clojure-cli)
  (cider-clojure-cli-aliases . ":dev"))
 (clojurescript-mode
  (cider-preferred-build-tool . shadow-cljs)))
