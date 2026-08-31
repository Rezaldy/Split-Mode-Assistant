@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.rizkybusiness.ai.assistant.index.BackendIndexApi
import com.rizkybusiness.ai.assistant.models.BackendModelsApi
import com.rizkybusiness.ai.assistant.search.BackendFileSearchApi
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
        remoteApi(remoteApiDescriptor<IndexApi>()) {
            BackendIndexApi()
        }
    }
}