# Paper Docs release rules.
#
# Most of what this app depends on ships its own consumer rules: Firebase, Play services, Compose,
# Credential Manager, OkHttp and kotlinx.coroutines all embed R8 rules in their AARs. So this file
# deliberately stays small. Every -keep here is dead weight in the shipped APK, and a rule that
# does not earn its place defeats the shrinking the release build type exists to enable.
#
# Firestore documents are read field by field (snapshot.getString("title")) rather than mapped to
# POJOs, so the app's own model classes need no keep rules. If that ever changes to
# DocumentSnapshot.toObject(), the affected classes must be added below.

# Kotlin metadata is read reflectively by kotlinx.serialization and some libraries.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

# Kotlin coroutines' internals are reached through generated service loaders.
-dontwarn kotlinx.coroutines.**

# Firebase's Firestore and Auth AARs reference optional gRPC/protobuf classes that are never
# present on Android. They are packaged on desktop, so silence the warnings rather than force
# stub classes into the APK.
-dontwarn com.google.firebase.firestore.**
-dontwarn io.grpc.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**

# Line numbers make Play Console crash reports readable; the source file name alone is not enough.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile