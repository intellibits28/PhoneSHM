#!/bin/bash
TOKEN=$(gcloud auth print-access-token)

# Read documents using jq
echo "Processing sessions..."
jq -c '.documents[]' sessions.json | while read i; do
    name=$(echo $i | jq -r '.name')
    buildingHash=$(echo $i | jq -r '.fields.buildingHash.stringValue // empty')
    
    # Check if it's test-session-12345
    if [[ "$name" == *"test-session-12345"* ]]; then
        echo "Deleting mock session: $name"
        curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "https://firestore.googleapis.com/v1/$name"
    elif [[ -z "$buildingHash" ]]; then
        echo "Deleting pre-fix orphan session: $name"
        curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "https://firestore.googleapis.com/v1/$name"
    else
        echo "Keeping valid session: $name with buildingHash: $buildingHash"
    fi
done
