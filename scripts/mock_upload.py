import argparse
import base64
import sys
import json
import firebase_admin
from firebase_admin import credentials, firestore

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--bin-file", required=True)
    parser.add_argument("--meta-file", required=True)
    args = parser.parse_args()

    firebase_admin.initialize_app()
    db = firestore.client()
    
    with open(args.meta_file, 'r') as f:
        meta_json = json.load(f)
        
    metadata = meta_json.get("metadata", {})
    metadata["sessionId"] = args.session_id
    
    with open(args.bin_file, 'rb') as f:
        bin_data = f.read()
        
    chunk_size = 500 * 1024
    file_length = len(bin_data)
    total_chunks = (file_length + chunk_size - 1) // chunk_size if file_length > 0 else 0
    
    metadata["totalChunks"] = total_chunks
    
    # Write chunks
    for i in range(total_chunks):
        start = i * chunk_size
        end = min((i + 1) * chunk_size, file_length)
        chunk_bytes = bin_data[start:end]
        base64_data = base64.b64encode(chunk_bytes).decode("utf-8")
        
        db.collection("sessions").document(args.session_id).collection("chunks").document(str(i)).set({
            "data": base64_data,
            "chunkIndex": i,
            "totalChunks": total_chunks
        })
        print(f"Uploaded chunk {i}")
        
    # Write metadata
    db.collection("sessions").document(args.session_id).set(metadata)
    print("Uploaded metadata")

if __name__ == "__main__":
    main()
