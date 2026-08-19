# R8 rules.
#
# Shrinking is a side benefit here; the reason this is on is obfuscation.
# Everything that makes a stolen credential valuable — where the device key is
# kept, how the bootstrap payload is unwrapped, which store holds what — is
# findable in a debuggable APK by reading class and method names. Renaming them
# does not make extraction impossible, it makes writing the extractor a
# reverse-engineering job instead of a reading job. That is the whole claim.
#
# Everything kept below is kept because something outside Java calls it by
# name, and a renamed name is a name that call cannot find. Each block says who
# the caller is, because "keep this, it broke once" ages into nobody daring to
# remove anything.

# -------------------------------------------------------------------- Gson --

# Gson maps JSON keys to fields by *reflection*. Two different situations, and
# only one of them is safe to obfuscate.
#
# `dto` is the unsafe one and the rule below is not negotiable: ProfileItem
# carries 59 fields and not one @SerializedName, so its JSON keys *are* its
# field names. Renaming them would not crash — it would silently write server
# configurations nothing can read back, which on an installed device means
# every saved server quietly turning to nulls.
-keep class com.rahgozar.app.dto.** { *; }

# The panel's own DTOs are the safe kind: every serialised field names itself
# with @SerializedName, so the field behind it can be renamed freely. This
# keeps that mapping working wherever it is used, including the private data
# classes nested inside PanelSync and PanelClient.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson reads generic types at runtime to resolve TypeToken and List<T> fields.
# Without Signature it sees raw types and hands back LinkedTreeMap.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Gson resolves enum constants by name.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --------------------------------------------------------------- gomobile --

# Xray and sing-box are Go, bound through gomobile. The binding is by name in
# both directions: native code looks up these classes and their methods, and
# go.Seq hands Java objects back across. Renaming any of it breaks at runtime
# with an unsatisfied lookup, not at build time.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class libbox.** { *; }
-keep class com.rahgozar.app.service.SingBoxNative { *; }

# --------------------------------------------------------------- openvpn3 --

# SWIG directors: C++ calls back into these Java classes by name, and a missing
# or renamed override surfaces as a pure-virtual call that aborts the whole
# tunnel process with SIGABRT. This has already cost one debugging session —
# see OpenVpnDelayTest and docs/CORES.md.
-keep class net.openvpn.ovpn3.** { *; }

# ------------------------------------------------- hev-socks5-tunnel (JNI) --

# The tun-to-SOCKS bridge is a C library that finds these four by name on this
# exact class. Nothing in Kotlin calls them from outside, so to R8 they are
# private plumbing.
#
# The default `-keepclasseswithmembernames class * { native <methods>; }` is
# present and still not enough, which is worth knowing before someone deletes
# this block as redundant: the functions are `external fun` inside a
# `companion object`, so Kotlin emits a static native method plus a synthetic
# `access$` bridge, R8 merges the two, and the merged result gets a new name.
# The mapping file showed `TProxyStartService -> a`, and the device answered
# with a JNI abort that took the whole tunnel process down:
#
#   JNI DETECTED ERROR IN APPLICATION: ... no static or non-static method
#   "Lcom/rahgozar/app/service/TProxyService;.TProxyIsRunning()Z"
-keep class com.rahgozar.app.service.TProxyService { *; }
-keep class com.rahgozar.app.service.TProxyService$* { *; }

# And the general form, for any native binding added later.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ------------------------------------------------------------------- MMKV --

# Native code constructs and calls into MMKV's Java side.
-keep class com.tencent.mmkv.** { *; }

# --------------------------------------------------------------- Android --

# Anything the platform instantiates by name is already kept from the merged
# manifest, so services, activities and receivers need no rule here. What the
# manifest does not cover is the callback interfaces native or framework code
# reaches through.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep the line table so a crash from the field is still readable, and rename
# the source file so it does not hand back the original layout for free.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------ noise --

# Optional dependencies these libraries reference but never load on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
