
import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.util.Log
import com.example.testactivity_arcoreissue.ui.ARCircleDetectionData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Class for saving the camera frame and depth data and confidence matrix as a JSOn file.
 */
class SessionSaver(var directoryName: String) {

    // Photo capturing variables
    private var width: Int = 0
    private var height: Int = 0
    private var arCoreRecordingName = ""
    private lateinit var savedBitmap: Bitmap
    private lateinit var savedFullFrameBitmap: Bitmap
    private lateinit var savedRAWFrameBitmap: Bitmap
    private lateinit var savedDepth: ByteBuffer
    private var saveDepthHeight: Int = 0
    private var saveDeptWidth: Int = 0
    private lateinit var savedConfidence: ByteBuffer

    companion object {
        private const val m_quality = 100
        private const val AR_RECORDING_BASE_NAME = "ar_recording"
        private const val IMAGE_BASE_NAME = "image"
        private const val IMAGE_BASE_NAME_CROPPED = "image_cropped"
        private const val IMAGE_BASE_NAME_ORIGINAL = "image_original"
        private const val DEPTH_BASE_NAME = "depth_data"
        private const val JSON_DATA_BASE_NAME = "image_data"

        private var resultsJsonFilePath: String = ""
        private lateinit var latestImgPath: String
    }

    fun getResultJSONFileName(): String {
        return resultsJsonFilePath
    }

    fun getLatestImgPath(): String {
        return latestImgPath
    }

    fun setSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun setDepthAndConfidence(saveDepth: Image, saveConfidence: Image) {
        this.savedDepth =
            cloneByteBuffer(saveDepth.planes[0].buffer.order(ByteOrder.nativeOrder()))!!
        this.savedConfidence =
            cloneByteBuffer(saveConfidence.planes[0].buffer.order(ByteOrder.nativeOrder()))!!
        this.saveDepthHeight = saveDepth.height
        this.saveDeptWidth = saveDepth.width
        saveDepth.close()
        saveConfidence.close()
    }

    private fun cloneByteBuffer(original: ByteBuffer): ByteBuffer? {
        val clone = ByteBuffer.allocate(original.capacity())
        original.rewind() // copy from the beginning
        clone.put(original)
        original.rewind()
        clone.flip()
        return clone
    }

    fun setBitmap(bitmap: Bitmap) {
        this.savedBitmap = bitmap
    }

    fun setFullFrameBitmap(bitmap: Bitmap) {
        this.savedFullFrameBitmap = bitmap
    }

    fun setRAWFrameBitmap(bitmap: Bitmap) {
        this.savedRAWFrameBitmap = bitmap
    }

    fun setDirectory(directoryName: String){
        this.directoryName = directoryName
    }

    @Throws(IOException::class)
    fun savePicture() {
        val imageName = IMAGE_BASE_NAME

        val out = File(this.directoryName, "$imageName.png")

        // Make sure the directory exists
        if (out.parentFile?.exists() == false) {
            out.parentFile?.mkdirs()
        }

        latestImgPath = out.absolutePath

        Log.d("IMG_SAVE", "Image path: $latestImgPath")
        // Write it to disk.
        val fos = FileOutputStream(out)
        savedBitmap.compress(Bitmap.CompressFormat.PNG, m_quality, fos)
        fos.flush()
        fos.close()
    }

    fun saveFullFrameBitmap() {
        val imageName = IMAGE_BASE_NAME_ORIGINAL
        val out = File(directoryName, "$imageName.png")

        // Make sure the directory exists
        if (out.parentFile?.exists() == false) {
            out.parentFile?.mkdirs()
        }
        Log.d("IMG_SAVE", "Image path:  $imageName")
        // Write it to disk.
        val fos = FileOutputStream(out)
        savedFullFrameBitmap.compress(Bitmap.CompressFormat.PNG, m_quality, fos)
        fos.flush()
        fos.close()
    }

    fun saveRAWFrameBitmap() {
        val imageName = IMAGE_BASE_NAME_CROPPED
        val out = File(directoryName, "$imageName.png")

        // Make sure the directory exists
        if (out.parentFile?.exists() == false) {
            out.parentFile?.mkdirs()
        }
        Log.d("IMG_SAVE", "Image path: $imageName")
        // Write it to disk.
        val fos = FileOutputStream(out)
        savedRAWFrameBitmap.compress(Bitmap.CompressFormat.PNG, m_quality, fos)
        fos.flush()
        fos.close()
    }

    fun saveDepth() {
        // creating new depth file
        val depthName = DEPTH_BASE_NAME
        val depthFile = File(directoryName, "$depthName.json")

        val depthJSONFile = JSONObject()
        depthJSONFile.put("ImageHeight", saveDepthHeight.toString())
        depthJSONFile.put("ImageWidth", saveDeptWidth.toString())

        // creating depth buffer and saving it
        val depthBuffer: ByteBuffer = savedDepth // .planes[0].buffer.order(ByteOrder.nativeOrder())
        val depthArray = ByteArray(depthBuffer.remaining())
        depthBuffer.get(depthArray)
        val depthBase64 = Base64.getEncoder().encodeToString(depthArray)

        // cerating confidence buffer and saving it
        val confidenceBuffer: ByteBuffer =
            savedConfidence // .planes[0].buffer.order(ByteOrder.nativeOrder())
        val confidenceArray = ByteArray(confidenceBuffer.remaining())
        confidenceBuffer.get(confidenceArray)
        val confidenceBase64 = Base64.getEncoder().encodeToString(confidenceArray)

        // adding depth and confidence to the json object
        depthJSONFile.put("depthBase64", depthBase64)
        depthJSONFile.put("confidenceBase64", confidenceBase64)

        val jsonString = depthJSONFile.toString()

        // Write it to disk.
        val depthFos = FileOutputStream(depthFile)
        depthFos.write(jsonString.toByteArray())
        depthFos.flush()
        depthFos.close()

        // closing saved images - not needed anymore
//        savedDepth.close()
//        savedConfidence.close()
    }

    fun getSavedBitmap(): Bitmap {
        return savedBitmap
    }

    fun getNewARrecordingDestination(): Uri {
        arCoreRecordingName = AR_RECORDING_BASE_NAME
        val out = File(directoryName + "-Recording", "recording.png")

        // Make sure the directory exists
        if (out.parentFile?.exists() == false) {
            out.parentFile?.mkdirs()
        }
        return Uri.fromFile(File(directoryName + "-Recording", "$arCoreRecordingName.mp4"))
    }

    fun removeARRecording() {
        val fdelete = File("$directoryName-Recording", "$arCoreRecordingName.mp4")
        if (fdelete.exists()) {
            fdelete.delete()
            Log.d("Recording", "Recording deleted.")
        }
    }

    fun saveCircleIdentificationInfoJSON(arCircleDetectionData: MutableList<ARCircleDetectionData>) {
        val resultsJsonFileName = JSON_DATA_BASE_NAME

        val jsonCircleInfo = Json.encodeToString(arCircleDetectionData)

        val jsonFile = File(directoryName, "$resultsJsonFileName.json")

        resultsJsonFilePath = jsonFile.absolutePath
        jsonFile.writeText(jsonCircleInfo)
    }
}
