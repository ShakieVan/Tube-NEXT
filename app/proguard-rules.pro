# GeckoView pulls in libraries with optional Java Beans references that are not
# part of Android's runtime. They are unused on Android, but R8 needs them
# silenced before it can shrink the release build.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
