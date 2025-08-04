from flask import Flask, request, jsonify
import os
import logging
import sys
import traceback
from werkzeug.utils import secure_filename

# Configure logging with more details
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

# Check NumPy version before importing other dependencies
try:
    import numpy as np
    numpy_version = np.__version__
    logger.info(f"NumPy version: {numpy_version}")
    
    # Check if NumPy version is compatible
    major_version = int(numpy_version.split('.')[0])
    if major_version >= 2:
        logger.warning("NumPy 2.x detected. Some dependencies may not work correctly.")
        logger.warning("Consider downgrading NumPy: pip install numpy==1.26.0")
except ImportError:
    logger.warning("NumPy not found. Installing required dependencies may fail.")

# Try importing the required libraries
try:
    from transformers import VisionEncoderDecoderModel, ViTImageProcessor, AutoTokenizer
    import torch
    from PIL import Image
    
    # Check if torch is available
    logger.info(f"PyTorch version: {torch.__version__}")
    logger.info(f"CUDA available: {torch.cuda.is_available()}")
    
    models_available = True
except ImportError as e:
    logger.error(f"Error importing required libraries: {str(e)}")
    logger.error("Please install required dependencies: pip install pillow transformers torch")
    models_available = False

app = Flask(__name__)

# Configure upload settings
UPLOAD_FOLDER = 'uploads'
if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # Limit uploads to 16MB

# Load models only if dependencies are available
model = None
feature_extractor = None
tokenizer = None
device = None

if models_available:
    logger.info("Loading image captioning model...")
    try:
        model = VisionEncoderDecoderModel.from_pretrained("nlpconnect/vit-gpt2-image-captioning")
        feature_extractor = ViTImageProcessor.from_pretrained("nlpconnect/vit-gpt2-image-captioning")
        tokenizer = AutoTokenizer.from_pretrained("nlpconnect/vit-gpt2-image-captioning")
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model.to(device)
        logger.info(f"Model loaded successfully. Using device: {device}")
    except Exception as e:
        logger.error(f"Error loading model: {str(e)}")
        logger.error(traceback.format_exc())
        model = None

@app.route('/', methods=['GET'])
def index():
    """Simple endpoint to test if server is running"""
    return jsonify({'status': 'Server is running'})

@app.route('/caption', methods=['POST'])
def caption_image():
    """Process image upload and return a caption"""
    logger.info("Received request to /caption")
    
    try:
        # Check if image is in request
        if 'image' not in request.files:
            logger.warning("No image in request")
            return jsonify({'error': 'No image part in the request'}), 400
        
        file = request.files['image']
        
        # Check if filename is empty
        if file.filename == '':
            logger.warning("Empty filename")
            return jsonify({'error': 'No selected file'}), 400
        
        # Process the file
        if file:
            filename = secure_filename(file.filename)
            file_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            file.save(file_path)
            logger.info(f"Image saved to {file_path}")
            
            # Check if model was loaded successfully
            if model is None:
                logger.warning("Model not loaded, returning dummy caption")
                return jsonify({'caption': 'Image captioning model not available. Please check server logs.'})
            
            try:
                # Generate caption using the model
                logger.info("Generating caption...")
                image = Image.open(file_path)
                
                # Convert RGBA to RGB if needed
                if image.mode == 'RGBA':
                    image = image.convert('RGB')
                
                # Log image details for debugging
                logger.info(f"Image size: {image.size}, mode: {image.mode}")
                
                # Process image with feature extractor
                pixel_values = feature_extractor(images=[image], return_tensors="pt").pixel_values
                logger.info(f"Feature extraction successful, tensor shape: {pixel_values.shape}")
                
                pixel_values = pixel_values.to(device)
                
                # Generate caption - MODIFIED to avoid beam search error
                logger.info("Generating caption with model...")
                with torch.no_grad():
                    # Use greedy search instead of beam search
                    output_ids = model.generate(
                        pixel_values, 
                        max_length=16,
                        num_beams=1,  # Changed from 4 to 1 to use greedy search
                        do_sample=False,
                        early_stopping=False
                    )
                
                # Decode the generated caption
                preds = tokenizer.batch_decode(output_ids, skip_special_tokens=True)
                caption = preds[0].strip()
                logger.info(f"Generated caption: {caption}")
                
                # Return the caption
                return jsonify({'caption': caption})
                
            except Exception as e:
                logger.error(f"Error generating caption: {str(e)}")
                logger.error(traceback.format_exc())
                
                # Fallback to a simpler generation method if the first one fails
                try:
                    logger.info("Trying alternative generation method...")
                    with torch.no_grad():
                        output_ids = model.generate(
                            pixel_values,
                            max_length=16,
                            do_sample=True,
                            top_k=50,
                            top_p=0.95,
                            num_return_sequences=1
                        )
                    
                    preds = tokenizer.batch_decode(output_ids, skip_special_tokens=True)
                    caption = preds[0].strip()
                    logger.info(f"Generated caption (alternative method): {caption}")
                    return jsonify({'caption': caption})
                except Exception as e2:
                    logger.error(f"Alternative generation also failed: {str(e2)}")
                    logger.error(traceback.format_exc())
                    return jsonify({'error': f'Error generating caption: {str(e)}'}), 500
    except Exception as e:
        logger.error(f"Error processing request: {str(e)}")
        logger.error(traceback.format_exc())
        return jsonify({'error': str(e)}), 500

@app.after_request
def after_request(response):
    """Add headers to every response to prevent caching"""
    response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    response.headers['Cache-Control'] = 'public, max-age=0'
    return response

if __name__ == '__main__':
    logger.info("Starting server on 0.0.0.0:5000")
    # Use threaded=True for better handling of multiple connections
    app.run(host='0.0.0.0', port=5000, debug=True, threaded=True)