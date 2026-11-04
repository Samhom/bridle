# Boot credential and resource ownership

All resource descriptors (including credential bean resolution, immutable settings, validation and
policy) are now prepared before any native client starts. A bad later entry therefore causes zero
native creates. If actual initialization later fails, only registrations owned by the binding close.
An application-owned runtime and unrelated registrations remain available.

A CredentialsProvider bean that implements CredentialSource is now automatically bound to the managed
handle. Previously properties accepted the source but never subscribed, so rotation/revocation was
silently absent. Subscription failures close the just-created registration. Credential revisions remain
independent of configuration revisions. Health reports OUT_OF_SERVICE while rotating and DOWN on
credential failure/revocation, without leaking endpoints, credential values or exception messages.

ResourceLifecycleTest uses the real runtime and RotatingCredentialsProvider rather than mocking the
credential observer: it observes active credential version changes, explicit source revocation, unchanged
configuration revision, native closes and survival of an unrelated registration. Existing context tests
cover property binding/context shutdown; isolated artifact acceptance covers optional-classpath startup.
