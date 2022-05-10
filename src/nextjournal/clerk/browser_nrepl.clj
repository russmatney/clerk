(ns nextjournal.clerk.browser-nrepl
  (:require
   [clojure.string :as str]))

;;;; Scratch

(comment

  (require '[nextjournal.clerk :as clerk])
  (require '[sci.nrepl.browser-proxy :as bp])
  (clerk/serve! {})
  (bp/serve! {:port 1340}) ;; web application, see view.clj
  #_(bp/halt!)
  (bp/start-browser-nrepl! {:port 1339}) ;; nREPL port
  (clerk/show! "notebooks/cljs_render_fn_file.clj")
  #_(bp/stop-browser-nrepl!)

  )
