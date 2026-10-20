# The host uses a parent-first class loader. Every bundled dependency must have
# a private class name so an older host Kotlin/OkHttp/Okio cannot shadow it.
-repackageclasses io.github.kiosknextcloud.internal
-keep class io.github.kiosknextcloud.** { *; }
-dontshrink
-dontoptimize
-allowaccessmodification
-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature,*Annotation*
# These are optional HTTP TLS/platform providers; they are not included or used
# by our Android platform's default TLS implementation.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn javax.annotation.**
-dontwarn java.lang.instrument.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn java.beans.**
-dontwarn java.net.http.**
-dontwarn org.slf4j.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
# R8 writes classfiles here; D8 desugars Java lambdas in the following stage.
-dontwarn java.lang.invoke.LambdaMetafactory
# Present on the JVM running the WebDAV fixture, absent from android.jar.
-dontwarn com.sun.net.httpserver.**
