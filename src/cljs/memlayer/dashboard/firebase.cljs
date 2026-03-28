(ns memlayer.dashboard.firebase
  (:require ["firebase/app" :refer [initializeApp]]
            ["firebase/auth" :refer [getAuth GoogleAuthProvider]]))

(def firebase-config
  #js {:apiKey "AIzaSyDdsLe3lQpQA0we4Z2h6yt8xchBQG2ItxI"
       :authDomain "memlayer-c69b1.firebaseapp.com"
       :projectId "memlayer-c69b1"})

(defonce app (initializeApp firebase-config))
(defonce auth (getAuth app))
(defonce google-provider (GoogleAuthProvider.))
