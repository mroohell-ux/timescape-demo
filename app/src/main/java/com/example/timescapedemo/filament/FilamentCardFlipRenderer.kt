package com.example.timescapedemo.filament

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View

/**
 * First-pass Filament renderer host for card flips.
 *
 * NOTE: this is the integration seam. Mesh/material authoring is intentionally isolated here so
 * we can iterate on physically-correct shading without touching adapter logic.
 */
class FilamentCardFlipRenderer(private val context: Context) {

    private val engine: Engine = Engine.create()
    private var swapChain: SwapChain? = null
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)

    init {
        view.scene = scene
        view.camera = camera
    }

    fun attach(surface: Surface) {
        detachSurface()
        swapChain = engine.createSwapChain(surface)
        configureCamera()
        // Mesh/material creation is delegated to the scene module in this first version.
    }

    fun updateCardTextures(front: Bitmap, back: Bitmap) {
        // TODO: Upload front/back bitmaps into Filament textures and bind to face material slots.
        // This method is the public contract used by the adapter/flip host.
        front.prepareToDraw()
        back.prepareToDraw()
    }

    fun setFlipProgress(progress: Float) {
        // TODO: Apply transform to thin-card entity (real geometry) around off-center hinge.
        // Kept as explicit API boundary for card motion tuning.
    }

    fun renderFrame() {
        val chain = swapChain ?: return
        if (renderer.beginFrame(chain)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun configureCamera() {
        // Strong perspective per product requirement.
        camera.setProjection(40.0, 1.0, 0.05, 10.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 0.0, 1.8, 0.0, 0.0, 0.0)
    }

    fun detachSurface() {
        swapChain?.let { chain ->
            engine.destroySwapChain(chain)
        }
        swapChain = null
    }

    fun destroy() {
        detachSurface()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroyEntity(cameraEntity)
        engine.destroy()
    }
}
