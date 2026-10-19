/*
 * Copyright (c) RikkaApps
 * Copyright (c) 2025-2026 FahrezRB
 *
 * Modified from Shizuku (https://github.com/RikkaApps/Shizuku).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package frb.axeron.api.core

import java.io.File

object Starter {

    const val KEY_PAIR = "axeron_adb_key_pair"

    private val starterFile =
        File(Engine.application.applicationInfo.nativeLibraryDir, "libaxeron.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "adb shell $userCommand"

    val internalCommand = "$userCommand --apk=${Engine.application.applicationInfo.sourceDir}"

    fun internalAdbCommand(keyPair: String) = "$userCommand --apk=${Engine.application.applicationInfo.sourceDir}; settings put global $KEY_PAIR $keyPair"
}