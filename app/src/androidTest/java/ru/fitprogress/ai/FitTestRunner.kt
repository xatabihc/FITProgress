package ru.fitprogress.ai

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** UI fixtures never share the normal history database or profile DataStore. */
class FitTestApplication : FitApp() {
    override val isolatedTestStorage = true
    override val backgroundSchedulingEnabled = false
}
class FitTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, FitTestApplication::class.java.name, context)
}
