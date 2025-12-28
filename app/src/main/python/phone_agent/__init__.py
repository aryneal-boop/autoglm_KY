import os

__path__ = [
    os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "autoglm", "phone_agent"))
]

from phone_agent.agent import PhoneAgent
from phone_agent.agent_ios import IOSPhoneAgent

__all__ = ["PhoneAgent", "IOSPhoneAgent"]
