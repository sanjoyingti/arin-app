package com.arin.builder

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Thin wrapper: the build itself runs in [BuildService] so it survives closing the app. */
class BuildViewModel(private val app: Application) : AndroidViewModel(app) {
    init {
        BuildRunner.load(app)
    }

    val timeline: List<TimelineItem> get() = BuildRunner.timeline
    val running: Boolean get() = BuildRunner.running
    val project: Project? get() = BuildRunner.project

    fun start(request: BuildRequest) = BuildService.start(app, request)

    /** Stops the build shown in the Build tab (other builds keep running). */
    fun stop() = BuildService.stop(app, BuildRunner.projectId)

    fun newProject() = BuildRunner.newProject(app)

    fun projects() = BuildRunner.list(app)

    fun openProject(id: String) = BuildRunner.open(app, id)

    fun deleteProject(id: String) = BuildRunner.delete(app, id)
}
