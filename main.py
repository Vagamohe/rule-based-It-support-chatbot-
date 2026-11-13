"""
University IT Support Chatbot (rule-based) - version 2
Run:  python it_chatbot.py
"""
import re
import threading
from fastapi import FastAPI
from pydantic import BaseModel 
from difflib import SequenceMatcher
from pyswip import Prolog

CONTACT = "IT office: it-support@univ.dz | Building B, Room 12 | Sun-Thu 08:30-16:00"
UNMATCHED_FILE = "unmatched.txt"   

# ------------------------------------------------------------
# 2. KNOWLEDGE BASE: one entry per IT problem
#    keywords = words students may write (English / French / Arabic)
# ------------------------------------------------------------
SMALL_TALK = {
    "greeting": {
        "keywords": [
            "hello", "hi", "salut", "bonjour",
            "salam", "slm", "مرحبا", "السلام عليكم"
        ],
        "answer": (
            "Hello! I am the university IT support bot. "
            "What is your problem?"
        )
    },

    "thanks": {
        "keywords": ["thanks", "thank you", "merci", "شكرا"],
        "answer": "You're welcome! Type another problem anytime."
    },

    "help": {
        "keywords": ["help", "menu", "aide", "مساعدة"],
        "answer": (
            "I can help with:\n"
            "- Wi-Fi\n"
            "- Passwords\n"
            "- University email\n"
            "- Moodle\n"
            "- Student portal (PROGRES)\n"
            "- Printers\n"
            "- Software installation"
        )
    }
}

YES = ["yes", "y", "oui", "ok", "solved", "نعم"]
NO = ["no", "n", "non", "not", "لا"]


# FastAPI endpoint for the Java interface
app = FastAPI(title="University IT Support Chatbot")
prolog = Prolog()
prolog.consult("rules.pl")
class ChatRequest(BaseModel):
    message: str
    session_id: str = "default"


# Keep follow-up conversations separate for each Java window.
SESSIONS = {}
SESSION_LOCK = threading.Lock()



def matches_keyword(user, keyword):
    user = user.lower().strip()
    keyword = keyword.lower().strip()

    if not user or not keyword:
        return False

    # Normalize hyphens and split into words
    user_words = re.findall(r"\w+", user)
    keyword_words = re.findall(r"\w+", keyword)

    if not user_words or not keyword_words:
        return False

    # Check each word in the user's message
    for word in user_words:
        for key in keyword_words:
            # Exact match or incomplete word
            if len(word) >= 2 and key.startswith(word):
                return True

            # Typo matching, e.g. moddle -> moodle
            if len(word) >= 4 and SequenceMatcher(
                None, word, key
            ).ratio() >= 0.75:
                return True

    return False

def get_response(message, session_id):
    user = message.lower().strip()

    if not user:
        return "Please type a message."

    with SESSION_LOCK:
        state = SESSIONS.setdefault(
            session_id,
            {"awaiting_resolution": False}
        )

        # Exit
        if user in ["exit", "quit", "bye"]:
            state["awaiting_resolution"] = False
            return "Goodbye!"

        # Handle yes/no answers
        if state["awaiting_resolution"]:

            if user in YES:
                state["awaiting_resolution"] = False
                return "Great! Glad I could help."

            if user in NO:
                state["awaiting_resolution"] = False
                return (
                    "Please contact our IT office:\n" + CONTACT
                )

            # Treat other messages as new questions
            state["awaiting_resolution"] = False

        # 1. Find small talk
        for category, data in SMALL_TALK.items():
            if any(
                matches_keyword(user, word)
                for word in data["keywords"]
            ):
                return data["answer"]

        # 2. Search for an IT problem in Prolog
        for fact in prolog.query("problem(Keyword, Answer)"):

            keyword = str(fact["Keyword"])

            if matches_keyword(user, keyword):
                answer = str(fact["Answer"])

                state["awaiting_resolution"] = True

                return (
                    answer
                    + "\n\nIs your problem solved? Reply yes or no."
                )

        # 3. Save unrecognized messages
        with open(UNMATCHED_FILE, "a", encoding="utf-8") as file:
            file.write(user + "\n")

        return (
            "Sorry, I couldn't identify your problem. "
            "Please describe it differently."
        )

@app.post("/")
def chat(request: ChatRequest):
    return {
        "reply": get_response(
            request.message,
            request.session_id
        )
    }
