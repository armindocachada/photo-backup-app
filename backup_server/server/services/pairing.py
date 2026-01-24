"""QR code generation for server pairing."""

import io
import json
from pathlib import Path

import qrcode


def generate_pairing_data(server_id: str, api_key: str) -> str:
    """Generate the JSON pairing data for the QR code.

    Only includes server_id and api_key. The server's IP address
    is discovered via mDNS, so it doesn't need to be in the QR code
    (and would become stale if the IP changes due to DHCP).
    """
    data = {
        "id": server_id,
        "key": api_key,
    }
    return json.dumps(data, separators=(",", ":"))


def generate_qr_ascii(data: str) -> str:
    """Generate an ASCII representation of a QR code."""
    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=1,
        border=1,
    )
    qr.add_data(data)
    qr.make(fit=True)

    # Generate ASCII art using block characters
    output = io.StringIO()
    qr.print_ascii(out=output, invert=True)
    return output.getvalue()


def save_qr_image(data: str, path: Path) -> None:
    """Save a QR code as a PNG image."""
    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=10,
        border=4,
    )
    qr.add_data(data)
    qr.make(fit=True)

    img = qr.make_image(fill_color="black", back_color="white")
    img.save(path)


def generate_pairing_qr(
    server_id: str,
    api_key: str,
    storage_path: Path,
) -> str:
    """
    Generate pairing QR code: returns ASCII art and saves PNG to storage.

    Returns the ASCII representation for display in terminal.
    """
    pairing_data = generate_pairing_data(server_id, api_key)

    # Save PNG image to storage directory
    qr_image_path = storage_path / "pairing_qr.png"
    save_qr_image(pairing_data, qr_image_path)

    # Return ASCII for terminal display
    return generate_qr_ascii(pairing_data)
