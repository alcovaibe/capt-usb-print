# JNI resolves these classes, fields and callbacks by their original names.
# The prebuilt LibreOffice engine cannot adapt to R8 renaming its Java bridge.
-keep class org.libreoffice.kit.** { *; }
-keep class com.cryonum.captusbprint.core.HiscoaNative { *; }
-keep class com.cryonum.captusbprint.office.OfficeSandbox { *; }
-keep class com.cryonum.captusbprint.office.OfficeKernelLimits { *; }

# OfficeWorkerService instantiates this backend by name in the worker process.
-keep,allowoptimization class com.cryonum.captusbprint.office.LibreOfficeWorkerBackend {
    public <init>();
}
