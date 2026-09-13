# The print framework instantiates the service by name from the manifest.
-keep class com.maxprint.epson.service.** { *; }
-keepclassmembers class * extends android.printservice.PrintService { *; }
