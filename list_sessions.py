import firebase_admin
from firebase_admin import credentials
from firebase_admin import firestore

# Initialize the Firebase Admin SDK
cred = credentials.Certificate('/data/data/com.termux/files/home/play-ground/ronin_shm/phoneshm-research-firebase-adminsdk-fbsvc-af2bf25fdc.json')
firebase_admin.initialize_app(cred)
db = firestore.client()

print("Fetching all session documents from Firestore for cleanup...")
sessions = db.collection('sessions').get()

if not sessions:
    print("The sessions collection is already empty.")
else:
    print(f"Found {len(sessions)} sessions:")
    for doc in sessions:
        print(f" - {doc.id}")
