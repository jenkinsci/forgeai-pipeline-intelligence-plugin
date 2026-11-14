from payments.discounts import apply_discount


def test_welcome_code():
    assert apply_discount(50, "WELCOME10") == 45
