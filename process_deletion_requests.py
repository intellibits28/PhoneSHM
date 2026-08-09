import firebase_admin
from firebase_admin import credentials
from firebase_admin import firestore
import json

# Initialize the Firebase Admin SDK
cred = credentials.Certificate('/data/data/com.termux/files/home/play-ground/ronin_shm/phoneshm-research-firebase-adminsdk-fbsvc-af2bf25fdc.json')
firebase_admin.initialize_app(cred)

db = firestore.client()

def delete_collection(coll_ref, batch_size=450):
    deleted_total = 0
    while True:
        docs = coll_ref.limit(batch_size).get()
        if not docs:
            break
        
        batch = db.batch()
        for doc in docs:
            batch.delete(doc.reference)
        
        batch.commit()
        deleted_total += len(docs)
        
    return deleted_total

def process_deletion_requests():
    print("Fetching pending deletion requests from Firestore...")
    requests = db.collection('deletionRequests').get()

    if not requests:
        print("No pending deletion requests found.")
        return

    for req_doc in requests:
        req_data = req_doc.to_dict()
        building_hash = req_data.get('buildingHash')
        req_id = req_doc.id

        if not building_hash:
            print(f"Skipping request {req_id}: No buildingHash provided.")
            continue

        print(f"\nProcessing deletion request {req_id} for buildingHash: {building_hash}")
        
        try:
            # Query sessions for this buildingHash
            sessions = db.collection('sessions').where('buildingHash', '==', building_hash).get()
            
            sessions_deleted = 0
            chunks_deleted = 0
            
            for session_doc in sessions:
                session_id = session_doc.id
                print(f"  - Deleting session {session_id} and its chunks...")
                
                # Delete chunks subcollection
                chunks_ref = session_doc.reference.collection('chunks')
                deleted_chunks = delete_collection(chunks_ref)
                chunks_deleted += deleted_chunks
                
                # Delete session document
                session_doc.reference.delete()
                sessions_deleted += 1
            
            # If we successfully reached here, delete the request document
            req_doc.reference.delete()
            print(f"Success! Request {req_id} completed. Deleted {sessions_deleted} sessions and {chunks_deleted} chunks.")
            
        except Exception as e:
            print(f"Error processing request {req_id}: {e}")
            print(f"Request {req_id} left in queue for retry.")

if __name__ == '__main__':
    process_deletion_requests()
