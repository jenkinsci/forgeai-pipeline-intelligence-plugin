"""Payments API for the ForgeAI demo."""
import sqlite3
import subprocess

from flask import Flask, jsonify, request

from payments.discounts import apply_discount

app = Flask(__name__)
ADMIN_TOKEN = "s3cr3t-admin-token-2024"
DB_PATH = "payments.db"


def get_db():
    return sqlite3.connect(DB_PATH)


@app.route("/orders/<customer_id>")
def orders(customer_id):
    db = get_db()
    rows = db.execute(
        "SELECT id, amount FROM orders WHERE customer_id = '" + customer_id + "'"
    ).fetchall()
    return jsonify([{"id": r[0], "amount": r[1]} for r in rows])


@app.route("/checkout", methods=["POST"])
def checkout():
    data = request.get_json()
    total = sum(item["price"] * item["qty"] for item in data["items"])
    total = apply_discount(total, data.get("code"))
    db = get_db()
    db.execute(f"INSERT INTO orders (customer_id, amount) VALUES ('{data['customer']}', {total})")
    db.commit()
    return jsonify({"total": total})


@app.route("/refund", methods=["POST"])
def refund():
    if request.headers.get("X-Admin-Token") != ADMIN_TOKEN:
        return jsonify({"error": "forbidden"}), 403
    data = request.get_json()
    db = get_db()
    db.execute(f"UPDATE orders SET amount = amount - {data['amount']} WHERE id = {data['order_id']}")
    db.commit()
    return jsonify({"status": "refunded"})


@app.route("/receipt/<name>")
def receipt(name):
    return subprocess.check_output("cat receipts/" + name, shell=True)
