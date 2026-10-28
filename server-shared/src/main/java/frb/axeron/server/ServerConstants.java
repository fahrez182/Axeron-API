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

package frb.axeron.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String SHIZUKU_MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api";
    // No package in it: the server sends it to whichever manager it found (setPackage).
    public static final String REQUEST_PERMISSION_ACTION = "AxRuntime.REQUEST_PERMISSION";
    public static final String REQUEST_BINDER_AXRUNTIME = "AxRuntime.REQUEST_BINDER";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
