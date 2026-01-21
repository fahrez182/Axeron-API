val apiVersionMajor = 1
val apiVersionMinor = 4
val apiVersionPatch = 0

val apiVersionName = "$apiVersionMajor.$apiVersionMinor.$apiVersionPatch"
val apiVersionCode =
    apiVersionMajor * 10_000 +
            apiVersionMinor * 1_000 +
            apiVersionPatch


extra["api_version_major"] = apiVersionMajor
extra["api_version_minor"] = apiVersionMinor
extra["api_version_patch"] = apiVersionPatch
extra["api_version_name"] = apiVersionName
extra["api_version_code"] = apiVersionCode
