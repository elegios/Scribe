(require '[cljs.build.api :as b])

(b/watch "src"
  {:main 'standalone-client.core
   :output-to "out/standalone_client.js"
   :output-dir "out"})
