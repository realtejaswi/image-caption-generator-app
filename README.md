# 📱 Image Caption Generator App

This project is an Android + Flask-based application that allows users to select or capture an image using a mobile app and receive an automatically generated caption using a deep learning model served via a Python Flask backend.

---

## 🧩 Project Structure

The project contains two main components:

### 🔸 Android App (Frontend)
**📂 `app/` folder — Native Android (Kotlin)**

- `MainActivity.kt`:  
  Handles the user interface and image capture/selection on the device. Sends the image to the backend API and displays the caption received.

- `AndroidManifest.xml`:  
  Sets app permissions and activity declarations.

- `res/`:  
  Contains layout files (`activity_main.xml`) and UI resources used in the app.

---

### 🔸 Flask Server (Backend)
**📂 `server/` folder — Python Flask API**

- `app.py`:  
  Main Flask server that receives images via HTTP POST requests.  
  It uses a pre-trained image captioning model (ViT-GPT2 from Hugging Face) to predict and return a caption for the input image.


---

## 🚀 How It Works

1. User opens the app and selects or captures an image.
2. The image is sent to the Flask API hosted locally or on a server.
3. The server processes the image and uses a deep learning model to generate a caption.
4. The caption is returned and displayed in the app UI.

---

## 🛠 Technologies Used

- **Android Frontend:** Kotlin, Android Studio
- **Backend API:** Flask, Python 3
- **Model:** CNN + RNN (e.g., InceptionV3 + LSTM)
- **Data Processing:** NumPy, TensorFlow/Keras, PIL
- **Communication:** REST API via HTTP POST

---

## 📺 App Demonstration
https://github.com/user-attachments/assets/0dc5eb09-a323-4990-bf01-222933334175
