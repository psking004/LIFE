with open('./app/src/test/java/com/example/AuthFlowTest.kt', 'r') as f:
    content = f.read()

content = content.replace('if (email == "23cse042@life.edu" || email == "23cse001@life.edu" || email == "23cse099@life.edu") {', 'val pwd = request["password"] ?: ""\n            if ((email == "23cse042@life.edu" && pwd == "student123") || (email == "23cse001@life.edu" && pwd == "pass123") || (email == "23cse099@life.edu" && pwd == "welcome")) {')

with open('./app/src/test/java/com/example/AuthFlowTest.kt', 'w') as f:
    f.write(content)
