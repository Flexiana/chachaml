(ns chachaml.serialize
  "Artifact serialization codec.

  Multimethod-dispatched encoding of Clojure values to bytes for
  storage, and decoding back. Built-in formats:

  - `:nippy` — Taoensso nippy freeze/thaw. Default for arbitrary
    Clojure values; round-trips most data structures.
  - `:edn`   — UTF-8 EDN. Human-readable; not safe for non-EDN values.
  - `:bytes` — raw `byte[]` passthrough.
  - `:file`  — a `java.io.File` or path string. Encoded as bytes; on
    decode the bytes are returned (not written to a file).

  Auto-detection of format from value type:

  - `byte[]`        → `:bytes`
  - `java.io.File`  → `:file`
  - everything else → `:nippy`

  Each `:format` has a registered MIME-style content type used when
  storing, and a reverse mapping used when loading."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(def ^:private content-type
  {:nippy "application/x-nippy"
   :edn   "application/edn"
   :bytes "application/octet-stream"
   :file  "application/octet-stream"})

(def ^:private content-type->format
  {"application/x-nippy"      :nippy
   "application/edn"          :edn
   "application/octet-stream" :bytes})

(defn auto-format
  "Return the default `:format` keyword for a value based on its type."
  [v]
  (cond
    (bytes? v)            :bytes
    (instance? File v)    :file
    (string? v)           :nippy ; strings serialise fine through nippy; treat as data, not a path
    :else                 :nippy))

(defmulti encode
  "Encode a value into a `{:bytes byte-array, :content-type string}`
  map. Dispatches on `:format` (`:nippy`, `:edn`, `:bytes`, `:file`)."
  :format)

(defmulti decode
  "Decode a `{:bytes byte-array, :format keyword}` map back into a
  Clojure value. Dispatches on `:format`."
  :format)

(defn format-from-content-type
  "Resolve a stored `content-type` string to one of the built-in
  format keywords. Falls back to `:bytes` if unknown."
  [ct]
  (get content-type->format ct :bytes))

;; --- :nippy -----------------------------------------------------------

(defmethod encode :nippy [{:keys [value]}]
  {:bytes        (nippy/freeze value)
   :content-type (content-type :nippy)})

(defmethod decode :nippy [{data :bytes}]
  (nippy/thaw data))

;; --- :edn -------------------------------------------------------------

(defmethod encode :edn [{:keys [value]}]
  {:bytes        (.getBytes (pr-str value) StandardCharsets/UTF_8)
   :content-type (content-type :edn)})

(defmethod decode :edn [{data :bytes}]
  (edn/read-string (String. ^bytes data StandardCharsets/UTF_8)))

;; --- :bytes -----------------------------------------------------------

(defmethod encode :bytes [{:keys [value]}]
  (when-not (bytes? value)
    (throw (ex-info ":bytes format requires a byte[] value"
                    {:type ::bad-value :got (class value)})))
  {:bytes        value
   :content-type (content-type :bytes)})

(defmethod decode :bytes [{data :bytes}]
  data)

;; --- :file ------------------------------------------------------------

(defmethod encode :file [{:keys [value]}]
  (let [^File f (io/file value)]
    (when-not (.exists f)
      (throw (ex-info "File does not exist"
                      {:type ::missing-file :path (.getAbsolutePath f)})))
    {:bytes        (Files/readAllBytes (.toPath f))
     :content-type (content-type :file)}))

(defmethod decode :file [{data :bytes}]
  data)
