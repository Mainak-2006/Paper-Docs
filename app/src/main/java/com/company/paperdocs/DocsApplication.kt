package com.company.paperdocs

import android.app.Application
import com.company.paperdocs.data.DocumentStore

class DocsApplication : Application() {
    val documents by lazy { DocumentStore(this) }
}
