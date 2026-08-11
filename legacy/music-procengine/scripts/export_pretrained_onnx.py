#!/usr/bin/env python3
"""
Music ProcEngine: ONNX Export Script
Downloads a pre-trained feature extractor (CLAP HTSAT) from Hugging Face,
wraps it to accept the engine's natively computed [1, 1, 96, 64] Mel-Spectrogram,
and exports it to an INT8 quantized ONNX model for bare-metal C++ inference.
"""

import os
import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import ClapAudioModel
from onnxruntime.quantization import quantize_dynamic, QuantType

class ONNXClapWrapper(nn.Module):
    def __init__(self):
        super().__init__()
        # Load the real pre-trained feature extractor from Hugging Face
        print("Downloading/Loading LAION CLAP HTSAT model...")
        self.model = ClapAudioModel.from_pretrained("laion/clap-htsat-fused")
        
    def forward(self, mel_spectrogram):
        # mel_spectrogram shape from C++ pipeline: [1, 1, 96, 64]
        # Hugging Face CLAP model expects input_features of shape [batch, 1, 1001, 64]
        # We perform a bilinear interpolation to map the 96 frames into 1001 frames natively on the graph
        x = F.interpolate(mel_spectrogram, size=(1001, 64), mode='bilinear', align_corners=False)
        
        # Execute inference
        out = self.model(input_features=x)
        
        # The pooler_output is the 512-D audio embedding vector
        # Return exact [512] float array shape for the C++ engine
        return out.pooler_output.squeeze(0)

def export_model():
    wrapper = ONNXClapWrapper()
    wrapper.eval()
    
    # Dummy input matching the C++ AudioPipeline output (96 frames, 64 mel bins)
    dummy_input = torch.randn(1, 1, 96, 64)
    
    onnx_fp32_path = "model_fp32.onnx"
    onnx_int8_path = "../model.onnx"
    
    print(f"Exporting FP32 model to {onnx_fp32_path}...")
    torch.onnx.export(
        wrapper, 
        dummy_input, 
        onnx_fp32_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={'input': {0: 'batch_size'}, 'output': {0: 'batch_size'}}
    )
    
    print(f"Quantizing to INT8 for dual-core CPU performance...")
    quantize_dynamic(
        model_input=onnx_fp32_path,
        model_output=onnx_int8_path,
        weight_type=QuantType.QUInt8
    )
    
    print(f"Done! Quantized INT8 model securely saved to {onnx_int8_path}")
    os.remove(onnx_fp32_path)

if __name__ == "__main__":
    export_model()
