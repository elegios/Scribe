(defproject scribe "0.1.0"
  :dependencies [[org.clojure/clojure       "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [reagent "0.6.0-alpha"]
                 [re-frame "0.7.0-alpha"]
                 [cljs-http "0.1.39"]]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-figwheel  "0.5.8"]]

  :hooks [leiningen.cljsbuild]

  :profiles {:dev {:cljsbuild
                   {:builds {:client {:source-paths ["devsrc"]
                                      :compiler     {:main scribe.dev
                                                     :asset-path "js"
                                                     :optimizations :none
                                                     :source-map true
                                                     :source-map-timestamp true}}}}}

             :prod {:cljsbuild
                    {:builds {:client {:compiler    {:optimizations :advanced
                                                     :elide-asserts true
                                                     :pretty-print false}}}}}}

  :figwheel {:repl false}

  :clean-targets ^{:protect false} ["resources/public/js"]

  :cljsbuild {:builds {:client {:source-paths ["src"]
                                :compiler     {:output-dir "resources/public/js"
                                               :output-to  "resources/public/js/client.js"}}}})
