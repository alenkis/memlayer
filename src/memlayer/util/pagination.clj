(ns memlayer.util.pagination
  "Shared pagination parsing for API handlers.")

(def default-max-page-size 100)

(defn parse-pagination
  "Parse limit/offset from query params map (string keys).
   Clamps limit to max-page-size (default 100), offset to 0."
  ([params] (parse-pagination params {}))
  ([params {:keys [max-page-size] :or {max-page-size default-max-page-size}}]
   (let [limit  (min (or (some-> (get params "limit") Integer/parseInt) max-page-size)
                     max-page-size)
         offset (max (or (some-> (get params "offset") Integer/parseInt) 0) 0)]
     {:limit limit :offset offset})))
