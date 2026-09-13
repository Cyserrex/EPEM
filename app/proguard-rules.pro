# The print framework instantiates the service by name from the manifest.
-keep class com.maxprint.epson.service.** { *; }
-keepclassmembers class * extends android.printservice.PrintService { *; }

# PreferenceFragmentCompat inflates preferences from res/xml/preferences.xml by class
# name, so the two-argument view constructor has to survive shrinking.
-keep public class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
}
