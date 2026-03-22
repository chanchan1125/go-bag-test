package com.gobag.domain.usecase

import com.gobag.domain.repository.SyncRepository

class RunSyncUseCase(private val syncRepository: SyncRepository) {
    suspend operator fun invoke() = syncRepository.run_sync_now()
}
