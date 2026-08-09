import firebase_admin
from firebase_admin import credentials
from firebase_admin import firestore
import json

# Initialize the Firebase Admin SDK
cred = credentials.Certificate('/data/data/com.termux/files/home/play-ground/ronin_shm/phoneshm-research-firebase-adminsdk-fbsvc-af2bf25fdc.json')
firebase_admin.initialize_app(cred)

db = firestore.client()

print("Fetching deletion requests from Firestore...")
requests = db.collection('deletionRequests').get()

if not requests:
    print("No deletion requests found.")
else:
    for doc in requests:
        print(f"Document ID: {doc.id} => Data: {doc.to_dict()}")
