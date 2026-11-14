CODES = {"WELCOME10": 0.10, "VIP25": 0.25, "STAFF50": 0.50}


def apply_discount(total, code):
    if code is None:
        return total
    rate = CODES.get(code.upper())
    if rate:
        total = total - total * rate
    if total > 100:
        total = total - 5
    return round(total, 2)
