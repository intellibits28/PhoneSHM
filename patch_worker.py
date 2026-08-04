import re

with open("core/storage/src/main/kotlin/com/ronin/phoneshm/core/storage/UploadSessionWorker.kt", "r") as f:
    content = f.read()

# We want to move the "3. Read metadata" and "4. Construct" and "5. Write Firestore document" 
# BEFORE "2. Upload .bin file in chunks"

# Split content into parts
part1 = content.split("// 2. Upload .bin file to Cloud Firestore in chunks")[0]

part2_chunks = "// 2. Upload .bin file to Cloud Firestore in chunks" + content.split("// 2. Upload .bin file to Cloud Firestore in chunks")[1].split("// 3. Read metadata JSON sidecar")[0]

part3_metadata = "// 3. Read metadata JSON sidecar" + content.split("// 3. Read metadata JSON sidecar")[1].split("// Clean up progress file since upload is complete")[0]

part4_cleanup = "// Clean up progress file since upload is complete" + content.split("// Clean up progress file since upload is complete")[1]

new_content = part1 + part3_metadata + part2_chunks + part4_cleanup

with open("core/storage/src/main/kotlin/com/ronin/phoneshm/core/storage/UploadSessionWorker.kt", "w") as f:
    f.write(new_content)
