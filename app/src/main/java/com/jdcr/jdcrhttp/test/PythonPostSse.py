# sse_server.py
from flask import Flask, Response, request
import time

app = Flask(__name__)

def generate_events(body: str):
    messages = [
        f"收到 POST 数据: {body}",
        "处理中...",
        "事件 1",
        "事件 2",
        "事件 3",
        "完成"
    ]
    for msg in messages:
        yield f"data: {msg}\n\n"
        time.sleep(1)

@app.route('/sse', methods=['POST'])
def sse():
    body = request.get_data(as_text=True)
    print(f"收到 POST: {body}")
    return Response(
        generate_events(body),
        mimetype='text/event-stream',
        headers={'Cache-Control': 'no-cache'}
    )

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5001, threaded=True)