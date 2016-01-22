(ns scribe.dev
  (:require [scribe.core :as scribe]
            [figwheel.client :as figwheel]))

(figwheel/start {:on-jsload scribe/run
                 :websocket-url "ws://localhost:3449/figwheel-ws"})

;
