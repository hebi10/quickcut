package com.hebi.quickcut.media

import java.io.File
import java.io.IOException

object ExportStagingFiles {
    private val ownedName = Regex("quickcut_(?:[0-9]+|(?:date_)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.mp4(?:\\.smartcut\\.mp4)?")

    fun cleanup(directory: File) {
        val files = directory.listFiles() ?: throw IOException("임시 저장소에 접근할 수 없습니다.")
        files.filter { it.isFile && ownedName.matches(it.name) }.forEach {
            if (!it.delete() && it.exists()) throw IOException("중단된 임시 영상을 정리하지 못했습니다. 저장 공간과 파일 접근을 확인해 주세요.")
        }
    }
}

