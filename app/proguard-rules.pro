# Keep Apache POI classes used for XLSX
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.commons.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.schemas.**