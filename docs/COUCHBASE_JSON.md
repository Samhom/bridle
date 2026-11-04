# Couchbase JSON boundary

Evidence: java-client 3.13.0 GetResult passes content bytes to its configured Transcoder only when
contentAs is invoked; contentAsBytes bypasses the transcoder in this exact version. RawJsonTranscoder does straight byte/string pass-through; default
JsonObject decoding can allocate a tree before an application checks maxDocumentBytes. The old facade
checked the typed result only after contentAsObject().toString(), and its untyped get had no size check.
Subdocument values wrapped in an array were not a proof that there was only one root JSON value.

Managed get now installs a per-request bounded transcoder with the leased generation's limit, and
validates size before constructing a JSON tree, String or application codec value. Native get-result
fixtures (including the contentAsBytes bypass counterexample) prove the ordering and CAS preservation. Transport/decompression has already produced the
byte array: this is a decode/application bound, not a claim of a server-frame or process-memory bound.

Writes validate exactly one JSON root and pass bytes through the native raw JSON transcoder. Objects,
arrays and scalars are supported; whitespace and numeric spelling are preserved. The driver owns flags,
CAS, durability and retry behavior. No polymorphic mapper or attacker-selected class is used.

The pinned driver's streaming parser rejects trailing values, duplicate object keys and malformed UTF-8.
The facade additionally rejects malformed UTF-16 strings, depth above 64, tokens above 1,000,000,
field names above 65,536 and numbers above 1,000 characters. These explicit facade limits can reject
previously accepted ambiguous/deep documents; use the native governed escape API for an intentionally
reviewed alternative policy. Subdocument limits are checked both before dispatch and against current
settings. Parser failures use a fixed message and do not retain the offending document in their cause.

Reference: https://docs.couchbase.com/java-sdk/current/howtos/transcoders-nonjson.html
