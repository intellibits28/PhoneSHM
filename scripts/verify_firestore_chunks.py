import argparse
import base64
import hashlib
import sys
import firebase_admin
from firebase_admin import credentials, firestore

def main():
    parser = argparse.ArgumentParser(description="Verify Firestore chunked upload and reconstruct .bin file.")
    parser.add_argument("--session-id", required=True, help="Session ID to verify")
    parser.add_argument("--service-account", required=False, help="Path to Firebase service account JSON. If omitted, uses default credentials.")
    parser.add_argument("--output", default="reconstructed.bin", help="Output file path for reconstructed binary")
    parser.add_argument("--original-file", required=False, help="Path to the original local .bin file, for byte-level comparison")
    args = parser.parse_args()

    print(f"Initializing Firebase...")
    if args.service_account:
        cred = credentials.Certificate(args.service_account)
        firebase_admin.initialize_app(cred, {
            'projectId': 'phoneshm-research'
        })
    else:
        firebase_admin.initialize_app(None, {
            'projectId': 'phoneshm-research'
        })
        
    db = firestore.client()

    print(f"\nFetching session metadata for {args.session_id}...")
    session_ref = db.collection("sessions").document(args.session_id)
    session_doc = session_ref.get()

    if not session_doc.exists:
        print(f"Error: Session document {args.session_id} not found!")
        sys.exit(1)

    metadata = session_doc.to_dict()
    total_chunks = metadata.get("totalChunks")
    if total_chunks is None:
        print("Error: 'totalChunks' field not found in metadata. Is this an older storage-based session?")
        sys.exit(1)

    print(f"Metadata indicates {total_chunks} total chunks.")

    print(f"\nFetching chunks from subcollection...")
    chunks_ref = session_ref.collection("chunks")
    chunks = list(chunks_ref.stream())
    
    if len(chunks) != total_chunks:
        print(f"Error: Expected {total_chunks} chunks but found {len(chunks)} in Firestore.")
        sys.exit(1)
        
    print(f"Successfully retrieved {len(chunks)} chunks.")

    # Sort chunks by chunkIndex
    chunks_data = []
    for chunk in chunks:
        data = chunk.to_dict()
        chunks_data.append({
            "index": data.get("chunkIndex"),
            "data": data.get("data")
        })
    
    chunks_data.sort(key=lambda x: x["index"])

    print("\nReconstructing file and verifying checksums...")
    hasher = hashlib.sha256()
    
    with open(args.output, "wb") as f:
        for i, chunk in enumerate(chunks_data):
            if chunk["index"] != i:
                print(f"Error: Missing chunk index {i}. Found {chunk['index']} instead.")
                sys.exit(1)
                
            raw_bytes = base64.b64decode(chunk["data"])
            f.write(raw_bytes)
            hasher.update(raw_bytes)
            print(f"  Processed chunk {i}/{total_chunks-1}: {len(raw_bytes)} bytes")

    final_hash = hasher.hexdigest()
    print(f"\nReconstruction complete!")
    print(f"Output file: {args.output}")
    print(f"Reconstructed SHA-256: {final_hash}")
    
    if args.original_file:
        print(f"\nComparing with original file: {args.original_file}")
        orig_hasher = hashlib.sha256()
        try:
            with open(args.original_file, "rb") as orig_f:
                while chunk := orig_f.read(8192):
                    orig_hasher.update(chunk)
            orig_hash = orig_hasher.hexdigest()
            print(f"Original SHA-256:      {orig_hash}")
            
            if final_hash == orig_hash:
                print("\nMATCH — reconstruction equals original")
            else:
                print("\nMISMATCH — reconstruction does not equal original")
                sys.exit(1)
        except Exception as e:
            print(f"Error reading original file: {e}")
            sys.exit(1)
    else:
        print("\nChunk count/order verified — NO byte-level comparison performed (provide --original-file to verify content integrity)")

if __name__ == "__main__":
    main()
