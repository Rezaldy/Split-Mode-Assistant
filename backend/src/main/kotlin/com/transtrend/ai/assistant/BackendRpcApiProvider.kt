@file:Suppress("UnstableApiUsage")

package com.transtrend.ai.assistant

import com.transtrend.ai.assistant.models.BackendModelsApi
import com.transtrend.ai.assistant.search.BackendFileSearchApi
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class BackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<ChatRepositoryRpcApi>()) {
            BackendChatRepositoryRpcApi()
        }
        remoteApi(remoteApiDescriptor<ModelsApi>()) {
            BackendModelsApi()
        }
        remoteApi(remoteApiDescriptor<FileSearchApi>()) {
            BackendFileSearchApi()
        }
    }
}