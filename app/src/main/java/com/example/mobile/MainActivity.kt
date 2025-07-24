package com.example.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val PICK_IMAGE = 100
    private val PERMISSION_REQUEST_CODE = 200
    private lateinit var imageView: ImageView
    private lateinit var captionText: TextView
    private var selectedImageUri: Uri? = null

    // Replace with your computer's actual IP address
    private val serverUrl = "http://192.168.1.2:5000/caption"

    // Maximum image size in bytes (2MB - reduced from 5MB)
    private val MAX_IMAGE_SIZE = 2 * 1024 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        captionText = findViewById(R.id.captionText)
        val selectButton: Button = findViewById(R.id.selectButton)

        selectButton.setOnClickListener {
            if (checkPermission()) {
                pickImageFromGallery()
            } else {
                requestPermission()
            }
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                PERMISSION_REQUEST_CODE
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickImageFromGallery()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        startActivityForResult(intent, PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            selectedImageUri = data.data
            imageView.setImageURI(selectedImageUri)
            selectedImageUri?.let { uploadImageToServer(it) }
        }
    }

    private fun uploadImageToServer(imageUri: Uri) {
        captionText.text = "Preparing image..."

        try {
            // Create a compressed image file from the URI
            val compressedFile = createCompressedImageFile(imageUri) ?: run {
                captionText.text = "Error: Could not process image"
                return
            }

            Log.d(TAG, "Created compressed file: ${compressedFile.absolutePath} (${compressedFile.length()} bytes)")
            captionText.text = "Uploading image..."

            // Create OkHttp client with timeouts
            val client = OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)  // Increased timeout
                .writeTimeout(120, TimeUnit.SECONDS)    // Increased timeout
                .readTimeout(120, TimeUnit.SECONDS)     // Increased timeout
                .retryOnConnectionFailure(true)
                .build()

            // Create multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "image.jpg",
                    compressedFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            // Build request
            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending request to $serverUrl")

            // Execute request asynchronously
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Request failed", e)
                    runOnUiThread {
                        captionText.text = "Connection error: ${e.message}"
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "Received response: ${response.code}")

                    try {
                        val responseBody = response.body?.string()
                        Log.d(TAG, "Response body: $responseBody")

                        if (!response.isSuccessful || responseBody == null) {
                            runOnUiThread {
                                captionText.text = "Error: Server returned ${response.code}. Check server logs."
                            }
                            return
                        }

                        try {
                            val json = JSONObject(responseBody)
                            if (json.has("caption")) {
                                val caption = json.getString("caption")
                                runOnUiThread {
                                    captionText.text = "Caption: $caption"
                                }
                            } else if (json.has("error")) {
                                runOnUiThread {
                                    captionText.text = "Error: ${json.getString("error")}"
                                }
                            } else {
                                runOnUiThread {
                                    captionText.text = "Invalid response format"
                                }
                            }
                        } catch (e: JSONException) {
                            Log.e(TAG, "JSON parsing error", e)
                            runOnUiThread {
                                captionText.text = "Error parsing response: ${e.message}"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Response processing error", e)
                        runOnUiThread {
                            captionText.text = "Error processing response: ${e.message}"
                        }
                    } finally {
                        response.body?.close()
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Exception in uploadImageToServer", e)
            captionText.text = "Error: ${e.message}"
        }
    }

    private fun createCompressedImageFile(uri: Uri): File? {
        try {
            // Open input stream from URI
            val inputStream = contentResolver.openInputStream(uri) ?: return null

            // Decode image size first to determine scaling
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStream.use { BitmapFactory.decodeStream(it, null, options) }

            // Calculate scaling factor
            var scale = 1
            while ((options.outWidth * options.outHeight * (1 / Math.pow(scale.toDouble(), 2.0))) > MAX_IMAGE_SIZE) {
                scale++
            }

            // Decode with scaling
            val scaledOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
            }

            val scaledInputStream = contentResolver.openInputStream(uri) ?: return null
            val bitmap = scaledInputStream.use { BitmapFactory.decodeStream(it, null, scaledOptions) }
                ?: return null

            // Create output file
            val outputFile = File.createTempFile("compressed_", ".jpg", cacheDir)

            // Compress and save with reduced quality
            val quality = 70  // Reduced quality from 85 to 70
            FileOutputStream(outputFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }

            // Log the final file size
            Log.d(TAG, "Compressed image size: ${outputFile.length()} bytes")

            // Recycle bitmap to free memory
            bitmap.recycle()

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating compressed image file", e)
            return null
        }
    }
}