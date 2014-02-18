(ns puppetlabs.kitchensink.ssl
  (:import (java.security Key KeyPair PrivateKey PublicKey KeyStore Security)
           (java.security.cert X509Certificate)
           (org.bouncycastle.openssl PEMParser PEMKeyPair PEMWriter)
           (org.bouncycastle.asn1.pkcs PrivateKeyInfo)
           (org.bouncycastle.openssl.jcajce JcaPEMKeyConverter)
           (org.bouncycastle.cert.jcajce JcaX509CertificateConverter)
           (javax.net.ssl KeyManagerFactory TrustManagerFactory SSLContext))
  (:use [clojure.tools.logging :as log]
        [clojure.java.io :only (reader writer)]
        [puppetlabs.kitchensink.core :only (enumerate keyset uuid)]))


(defn get-key-manager-factory
  "Given a map containing a KeyStore and keystore password (e.g. as generated by
  pems->key-and-trust-stores), return a KeyManagerFactory that contains the
  KeyStore."
  [{:keys [keystore keystore-pw]}]
  {:pre [(instance? KeyStore keystore)
         (string? keystore-pw)]
   :post [(instance? KeyManagerFactory %)]}
  (doto (KeyManagerFactory/getInstance
          (KeyManagerFactory/getDefaultAlgorithm))
    (.init keystore (.toCharArray keystore-pw))))

(defn get-trust-manager-factory
  "Given a map containing a trust store (e.g. as generated by
  pems->key-and-trust-stores), return a TrustManagerFactory that contains the
  trust store."
  [{:keys [truststore]}]
  {:pre [(instance? KeyStore truststore)]
   :post [(instance? TrustManagerFactory %)]}
  (doto
      (TrustManagerFactory/getInstance
        (TrustManagerFactory/getDefaultAlgorithm))
    (.init truststore)))

(defn keystore
  "Create an empty in-memory Java KeyStore object."
  []
  (doto (KeyStore/getInstance "JKS")
    (.load nil)))

(defn pem->objs
  "Given a file path (or any other type supported by clojure's `reader`), reads
  PEM-encoded objects and returns a collection of objects of the
  corresponding type from `java.security`."
  [pem]
  {:post [(coll? %)]}
  (let [pemreader (PEMParser. (reader pem))]
    (loop [objs []]
      (let [obj (.readObject pemreader)]
        (if obj
          (do
            (log/debug (format "Loaded PEM object of type '%s' from '%s'" (class obj) pem))
            (recur (conj objs obj)))
          objs)))))

(defn obj->pem!
  "Encodes an object in PEM format, and writes it to a file (or other stream).  Arguments:

  `obj`: the object to encode and write.  Must be of a type that can be encoded
         to PEM; usually this is limited to certain types from the `java.security`
         packages.

  `pem`:   the file path to write the PEM output to.  (Alternately, you may pass a `Writer`
         or any other type that is supported by clojure's `writer`.)"
  [obj pem]
  (with-open [w (writer pem)]
    (doto (PEMWriter. w)
      (.writeObject obj)
      (.flush))))

(defn pem->certs
  "Given the path to a PEM file (or some other object supported by
  clojure's `reader`), decodes the contents into an collection of
  `X509Certificate` instances."
  [pem]
  {:post [(every? (fn [x] (instance? X509Certificate x)) %)]}
  (let [converter (JcaX509CertificateConverter.)]
    (map #(.getCertificate converter %) (pem->objs pem))))

(defn obj->private-key
  "Decodes the given object (read from a .PEM file via `pem->objs`)
  into an instance of `PrivateKey`"
  [obj]
  {:post [(instance? PrivateKey %)]}
  (cond
   (instance? PrivateKeyInfo obj) (.getPrivateKey (JcaPEMKeyConverter.) obj)
   ;; Certain PEMs will hand back a keypair with a nil public key
   (instance? PEMKeyPair obj)     (.getPrivate (.getKeyPair (JcaPEMKeyConverter.) obj))
   :else
   (throw (IllegalArgumentException. (format "Expected a KeyPair or PrivateKey, got %s" obj)))))

(defn pem->private-keys
  "Given the path to a PEM file (or some other object supported by clojure's `reader`),
  decodes the contents into a collection of `PrivateKey` instances."
  [pem]
  {:post [(every? (fn [x] (instance? PrivateKey x)) %)]}
  (map obj->private-key (pem->objs pem)))

(defn key->pem!
  "Encodes a public or private key to PEM format, and writes it to a file (or other
  stream).  Arguments:

  `key`: the key to encode and write.  Usually an instance of `PrivateKey` or `PublicKey`.
  `f`:   the file path to write the PEM output to.  (Alternately, you may pass a `Writer`
         or any other type that is supported by clojure's `writer`.)"
  [key pem]
  {:pre  [(instance? Key key)]}
  (obj->pem! key pem))

(defn assoc-cert!
  "Add a certificate to a keystore.  Arguments:

  `keystore`: the `KeyStore` to add the certificate to
  `alias`:    a String alias to associate with the certificate
  `cert`:     an `X509Certificate` to add to the keystore"
  [keystore alias cert]
  {:pre  [(instance? KeyStore keystore)
          (string? alias)
          (instance? X509Certificate cert)]
   :post [(instance? KeyStore %)]}
  (.setCertificateEntry keystore alias cert)
  keystore)

(defn assoc-certs-from-file!
  "Add all certificates from a PEM file to a keystore.  Arguments:

  `keystore`: the `KeyStore` to add certificates to
  `prefix`:   an alias to associate with the certificates. each
              certificate will have a numeric index appended to
              its alias (starting with '-0'
  `pem`:      the path to a PEM file containing the certificate"
  [keystore prefix pem]
  (doseq [[i cert] (enumerate (pem->certs pem))
          :let [alias (format "%s-%d" prefix i)]]
    (assoc-cert! keystore alias cert))
  keystore)

(defn assoc-private-key!
  "Add a private key to a keystore.  Arguments:

  `keystore`:    the `KeyStore` to add the private key to
  `alias`:       a String alias to associate with the private key
  `private-key`: the `PrivateKey` to add to the keystore
  `pw`:          a password to use to protect the key in the keystore
  `cert`:        the `X509Certificate` for the private key; a private key
                 cannot be added to a keystore without a signed certificate."
  [keystore alias private-key pw cert]
  {:pre  [(instance? KeyStore keystore)
          (string? alias)
          (instance? PrivateKey private-key)
          (or (nil? cert)
              (instance? X509Certificate cert))]
   :post [(instance? KeyStore %)]}
  (.setKeyEntry keystore alias private-key (char-array pw) (into-array [cert]))
  keystore)

(defn assoc-private-key-file!
  "Add a private key to a keystore.  Arguments:

  `keystore`:        the `KeyStore` to add the private key to
  `alias`:           a String alias to associate with the private key
  `pem-private-key`: the path to a PEM file containing the private key to add to
                     the keystore
  `pw`:              a password to use to protect the key in the keystore
  `pem-cert`:        the path to a PEM file containing the certificate for the
                     private key; a private key cannot be added to a keystore
                     without a signed certificate."
  [keystore alias pem-private-key pw pem-cert]
  (let [keys  (pem->private-keys pem-private-key)
        certs (pem->certs pem-cert)]
    (when (> (count keys) 1)
      (throw (IllegalArgumentException.
              (format "The PEM file %s contains more than one key" pem-private-key))))
    (when (> (count certs) 1)
      (throw (IllegalArgumentException.
              (format "The PEM file %s contains more than one certificate" pem-private-key))))
    (assoc-private-key! keystore alias (first keys) pw (first certs))))

(defn pems->key-and-trust-stores
  "Given pems for a certificate, private key, and CA certificate, creates an
  in-memory KeyStore and TrustStore.

  Argument should be a map containing the keys `:cert`, `:key`, and `:ca-cert`.
  Each value must be an object suitable for use with clojure's `reader`, and
  reference a PEM that contains the appropriate cert/key.

  Returns a map containing the following keys:

  * `:keystore` - an instance of KeyStore initialized with the cert and private key
  * `:keystore-pw` - a string containing a dynamically generated password for the KeyStore
  * `:truststore` - an instance of KeyStore containing the CA cert."
  [cert key ca-cert]
  {:pre  [key
          cert
          ca-cert]
   :post [(map? %)
          (= (keyset %) #{:keystore :truststore :keystore-pw})
          (instance? KeyStore (:keystore %))
          (string? (:keystore-pw %))
          (instance? KeyStore (:truststore %))]}
  (let [truststore  (-> (keystore)
                        (assoc-certs-from-file! "CA Certificate" ca-cert))
        keystore-pw (uuid)
        keystore    (-> (keystore)
                        (assoc-private-key-file! "Private Key" key keystore-pw cert))]
    {:keystore      keystore
     :keystore-pw   keystore-pw
     :truststore    truststore}))

(defn pems->ssl-context
  "Given pems for a certificate, private key, and CA certificate, creates an
  in-memory SSLContext initialized with a KeyStore/TrustStore generated from
  the input certs/key.

  Argument should be a map containing the keys `:cert`, `:key`, and `:ca-cert`.
  Each value must be an object suitable for use with clojure's `reader`, and
  reference a PEM that contains the appropriate cert/key.

  Returns the SSLContext instance."
  [cert key ca-cert]
  {:pre  [key
          cert
          ca-cert]
   :post [(instance? SSLContext %)]}
  (let [stores  (pems->key-and-trust-stores cert key ca-cert)
        kmf     (get-key-manager-factory stores)
        tmf     (get-trust-manager-factory stores)
        ctxt    (SSLContext/getInstance "SSL")]
    (.init ctxt (.getKeyManagers kmf) (.getTrustManagers tmf) nil)
    ctxt))
