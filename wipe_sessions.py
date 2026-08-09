import firebase_admin
from firebase_admin import credentials
from firebase_admin import firestore

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

def wipe_all_sessions():
    print("Fetching ALL session document references (including phantom documents without metadata)...")
    # Use list_documents() to find phantom documents that only contain subcollections
    session_refs = list(db.collection('sessions').list_documents())

    if not session_refs:
        print("The sessions collection is completely empty.")
        return

    sessions_deleted = 0
    chunks_deleted = 0
    
    for session_ref in session_refs:
        session_id = session_ref.id
        print(f"  - Deleting session {session_id} and its chunks...")
        
        # Delete chunks subcollection
        chunks_ref = session_ref.collection('chunks')
        deleted_chunks = delete_collection(chunks_ref)
        chunks_deleted += deleted_chunks
        
        # Delete session document (even if it's phantom, this cleans it up)
        session_ref.delete()
        sessions_deleted += 1
        
    print(f"\nSuccess! Wipe completed. Deleted {sessions_deleted} sessions and {chunks_deleted} chunks.")

if __name__ == '__main__':
    wipe_all_sessions()
