import re

with open("core/storage/src/main/kotlin/com/ronin/phoneshm/core/storage/UploadSessionWorker.kt", "r") as f:
    content = f.read()

replacement = """
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val value = devObj.get(k)
                            if (value is org.json.JSONArray) {
                                val list = mutableListOf<Any>()
                                for (i in 0 until value.length()) {
                                    list.add(value.get(i))
                                }
                                deviceReportMap[k] = list
                            } else {
                                deviceReportMap[k] = value
                            }
                        }
"""

content = re.sub(
    r"while \(keys\.hasNext\(\)\) \{\s*val k = keys\.next\(\)\s*deviceReportMap\[k\] = devObj\.get\(k\)\s*\}",
    replacement.strip(),
    content
)

replacement2 = """
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val value = metaObj.get(k)
                            if (value is org.json.JSONArray) {
                                val list = mutableListOf<Any>()
                                for (i in 0 until value.length()) {
                                    list.add(value.get(i))
                                }
                                metadataMap[k] = list
                            } else {
                                metadataMap[k] = value
                            }
                        }
"""

content = re.sub(
    r"while \(keys\.hasNext\(\)\) \{\s*val k = keys\.next\(\)\s*metadataMap\[k\] = metaObj\.get\(k\)\s*\}",
    replacement2.strip(),
    content
)

with open("core/storage/src/main/kotlin/com/ronin/phoneshm/core/storage/UploadSessionWorker.kt", "w") as f:
    f.write(content)
