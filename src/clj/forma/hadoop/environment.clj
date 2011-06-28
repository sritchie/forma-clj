(ns forma.hadoop.environment
  (:use [pallet.resource :only (phase)]
        [pallet.resource.package :only (package-manager)]
        [pallet.compute.vmfest :only (parallel-create-nodes)])
  (:require [pallet.compute :as compute]
            [pallet.core :as core])
  (:import [java.net InetAddress]))

;; ### EC2 Environment

(def remote-env
  (let [default-image  {:image
                        {:os-family :ubuntu
                         :os-64-bit true}}]
    {:tags (zipmap [:hadoop :namenode :jobtracker :slavenode]
                   (repeat default-image))
     :algorithms {:lift-fn pallet.core/parallel-lift
                  :converge-fn pallet.core/parallel-adjust-node-counts}}))

(def ec2-service (compute/service :aws))

;; ### Local Environment

(def vm-service (compute/service :virtualbox))

(def parallel-env
  {:algorithms
   {:lift-fn core/parallel-lift
    :vmfest {:create-nodes-fn parallel-create-nodes}
    :converge-fn core/parallel-adjust-node-counts}})

(def local-proxy (format "http://%s:3128"
                         (.getHostAddress
                          (InetAddress/getLocalHost))))

(def local-node-specs
  (merge remote-env
         {:proxy local-proxy
          :phases {:bootstrap
                   (phase
                    (package-manager
                     :configure :proxy local-proxy))}}))

(def vm-env
  (merge local-node-specs parallel-env))