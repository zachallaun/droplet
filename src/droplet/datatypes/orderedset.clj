(ns droplet.datatypes.orderedset
  (require clojure.set
           [droplet.datalog :as d]
           [droplet.test :as kvs])
  (use droplet
       clojure.test
       clojure.pprint))

;; Provides an ordered set lattice inspired by Treedoc:
;;  http://hal.inria.fr/inria-00445975
;;  http://run.unl.pt/bitstream/10362/7802/1/Sousa_2012.pdf

;; An ordered set has the two following operations as well as the lattice lte? and join methods:
;;  insert(prev-element, elem)
;;  delete(element)
;;
;; Each item in the ordered set contains a path, which is a list of {:path [{:branch :disamb}] :val val} and the payload value
;; A disambiguator for a non-tail node is omitted if the path passes through a major node. It is included if the path
;;  goes through a minor node
;; A disambiguator is a (clock, siteid) pair where clock is a lamport clock, the clock value that a node had when inserting that value
;;
;; Note that we diverge from the algorithm described in the Souza2012 paper when it comes to the Version Vector. The paper described
;;  the version vector as containing {disamb(node), counter} pairs, but if we only keep track of disambiguators, in the compare function
;;  we cannot get a list of node paths that do not exist in the set *but* have have an entry in the version vector---we would need to
;;  keep a list of "removed nodes" by disambiguator. Instead we make the VV key be the path+disambiguator, so we can retrieve the path
;;  directly.
;;

(def clock (atom 0))

(defn clock-value
  "Next value for this node's vector clock id
  TODO"
  []
  (swap! clock inc))

(defn siteid<?
  "Returns a < relation for two site ids, that are usually MAC addresses.

  TODO real mac addresses.. for now just string comparison"
  [macaddra macaddrb]
  (< (.compareTo macaddra macaddrb) 0))

(defn disamb<?
  [{clockl :clock siteidl :siteid} {clockr :clock siteidr :siteid}]
  (if (not= clockl clockr)
    (< clockl clockr) ;; If there is a lamport clock ordering, use that
    (siteid<? siteidl siteidr))) ;; else order by MAC address

(defn- single?
  "Returns true if the collection contains one item; else false."
  [coll]
  (= 1 (count coll)))

;; Compare by path and break ties (mini-nodes are siblings in same major
;; node) with disambiguator
(defn item<?
  [{pathl :path} {pathr :path}]
  (loop [pathl pathl
         pathr pathr]
    (let [{l :branch l-disamb :disamb} (first pathl)
          {r :branch r-disamb :disamb} (first pathr)
          ;; mininode if this is not the last node and we have a disambiguator
          l-is-mininode (and l-disamb (> (count pathl) 1))]
      (cond
        (nil? l) (= r 1)
        (nil? r) (= l 0)
        ;; Comparing mininode to end node or other mininode, order by disambiguator
        (= l r) (if (or (and l-is-mininode r-disamb)
                        ;; Finishes with two mini-siblings, order by disambiguator
                        (and (single? pathl) (single? pathr)))
                   (disamb<? l-disamb r-disamb)
                   (recur (rest pathl) (rest pathr)))
        :else    (< l r)))))

(defn path-len
  "Returns the path length for this path, which is usually the number of pairs
   except for the root which has a pair but no branch"
  [path]
  (if (and (single? path) (nil? (:branch (first path))))
    0
    (count path)))

(defn ancestor?
  "Returns if the first path is an ancestor to the second"
  [pathl pathr]
  (when (< (path-len pathl) (path-len pathr))
    (loop [[nodel & morel] pathl
           [noder & morer] pathr]
      (let [{l :branch} nodel
            {r :branch} noder]
        (or (nil? l) (and (= l r) (recur morel morer)))))))

(defn mini-sibling?
  "Returns true if the two paths are mini-siblings of each other,
   that is, they have the same path but differ in disambiguators"
   [pathl pathr]
   (and (= (count pathl) (count pathr))
        (= (map :branch pathl)
           (map :branch pathr))))

(defn pathnode
  "Returns a new pathnode with the given branch
   and automatically-filled in disambiguator

   TODO use real lamport clock and macaddr for this!"
   [branch]
   {:branch branch :disamb {:clock (clock-value) :siteid "MACADDRESS"}})

(defn extend-path
  "Extends the given path with a new tail element in the path. Will
   remove  any disambiguator in the last path node if it's a major node
   before extending

   TODO check for major node, removes unconditionally right now"
   [oldpath newnode]
   ;; Filter out root node with {branch :nil}
   (let [cleanpath (filter :branch oldpath)]
     (if (empty? cleanpath)
       [newnode]
       (conj (vec (butlast cleanpath))
             (select-keys (last cleanpath) [:branch])
             newnode))))

(defn disamb-for-path
  "Returns the disambiguator for the node described by the given path"
  [path]
  (:disamb (last path)))

(defn rootnode
  []
  [(pathnode nil)])

(defn new-id
  "Returns a new position id between the two given ids

  There must be no already-existing id between the two paths.
  They must be adjacent"
  ;; TODO remove disambiguator from any non-mini node, we we only
  ;;      need it at the end
  [{pathl :path} {pathr :path}]
  (cond
    (and (nil? pathl) (nil? pathr))
    (rootnode)

    (or (and (nil? pathl) (seq pathr))
        (ancestor? pathl pathr))
    (extend-path pathr (pathnode 0))

    ;; Maintain disambiguator if making a child of a mininode
    (mini-sibling? pathl pathr)
    (conj pathl (pathnode 1))

    :else (extend-path pathl (pathnode 1))))

;; Steps to an insert:
;;  1) Find next item after position
;;  2) Get path/id between pos and next
;;  3) Insert new item with id
;;  4) Update lamport clock
;;
;; NOTE: Extremely inefficient at the moment. Finding the required node is worst-case
;;        O(n) if the node is at the end. O(n) inserts are no good.
;;       Consider better ways to do this---might require changing the API (just inserting based
;;         on the data means we'll always have to linear search for the right node)
;;       Try a zipper?
;;
;; Same goes for remove
(defn oset-insert
  "Inserts a new item in the ordered set after the specified item. If nil is supplied,
  inserts at the beginning.
  Returns the new ordered set"
  [{oset :oset :as oset-lattice} prev-data data]
  (let [from-prev (seq (drop-while #(not= (:val %) prev-data) oset))
        before (first from-prev)
        after (if before (second from-prev) (first oset))
        path (new-id before after)]
    (if (or
          (and (nil? before) (not (nil? prev-data)))
          (= (:val after) data))
      oset-lattice ;; If we didn't find the previous term (but it was specified) ignore
      (-> oset-lattice ;; Insert item and update item in vc
        (update-in [:oset] conj {:path path :val data})
        (update-in [:vc] #(join % {path (->Max (:clock (disamb-for-path path)))}))))))

(defn oset-remove
  "Removes the desired item from this set and returns it"
  [{oset :oset :as oset-lattice} item]
  (if-let [found (first (filter #(= (:val %) item) oset))]
    (assoc oset-lattice :oset (disj oset found))
    oset-lattice))

(defn ordered-set
  []
  (sorted-set-by item<?))

(defn removed-set
  [oset-lattice]
  (let [existing-paths (for [node (:oset oset-lattice)] (:path node))]
    (set (for [item (:vc oset-lattice) :when (not (some #{(first item)} existing-paths))] (first item)))))

(defrecord OrderedSet [oset vc]
  SemiLattice
  (bottom [this]
    (->OrderedSet (ordered-set) (kvs/->Causal {} nil)))
  (lte? [this that]
    (let [this-removed (removed-set this)
          that-removed (removed-set that)]
      (and (lte? (:vc this) (:vc that))
           (clojure.set/subset? this-removed that-removed))))
  (join [this that]
    (let [new-in-that (set (for [item (:oset that)
                                  :when (not (some #{(:path item)} (:vc this)))]
                              item)) ;; New items added in that that were not removed in this
          removed-in-this (set (for [item (clojure.set/difference (:oset this) (:oset that))
                                      :when (some #{(:path item)} (keys (:vc that)))]
                                  item)) ;; Items removed in this which that already knew about
          updated-set (apply sorted-set-by item<?
                        (clojure.set/difference
                          (clojure.set/union (:oset this) new-in-that)
                          removed-in-this))]
      (->OrderedSet updated-set (join (:vc this) (:vc that))))))

