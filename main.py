"""
University IT Support Chatbot (rule-based) - version 2
Run:  python it_chatbot.py
"""
import re
import threading
from fastapi import FastAPI
from pydantic import BaseModel 
from difflib import SequenceMatcher

CONTACT = "IT office: it-support@univ.dz | Building B, Room 12 | Sun-Thu 08:30-16:00"
UNMATCHED_FILE = "unmatched.txt"   

# ------------------------------------------------------------
# 2. KNOWLEDGE BASE: one entry per IT problem
#    keywords = words students may write (English / French / Arabic)
# ------------------------------------------------------------
RULES = {
    "wifi": {
        "keywords": ["wifi", "wi-fi", "internet", "eduroam", "reseau", "connexion",
                     "واي فاي", "انترنت", "الانترنت", "الشبكة"],
        "answer": ("Wi-Fi problem:\n"
                   "1. Turn Wi-Fi off and on again.\n"
                   "2. 'Forget' the eduroam network and reconnect.\n"
                   "3. Check that you type your full university login.\n"
                   "4. Try in another place: the signal may be weak in your room."),
    },
    "password": {
        "keywords": ["password", "mot de passe", "mdp", "oublie", "forgot", "reset",
                     "كلمة السر", "كلمة المرور", "نسيت"],
        "answer": ("Forgot your password:\n"
                   "1. Open the login page and click 'Forgot password'.\n"
                   "2. Follow the link sent to your recovery email.\n"
                   "3. No access to that email? Come to the IT office with your student card."),
    },
    "email": {
        "keywords": ["email", "e-mail", "mail", "messagerie", "outlook", "gmail",
                     "البريد", "ايميل"],
        "answer": ("University email problem:\n"
                   "1. Use your full university address (name@univ...).\n"
                   "2. Check your password (see 'forgot password' if needed).\n"
                   "3. Mailbox full? Delete old messages and empty the trash."),
    },
    "moodle": {
        "keywords": ["moodle", "cours", "course", "e-learning", "elearning", "plateforme",
                     "مودل", "الدروس"],
        "answer": ("Moodle / e-learning:\n"
                   "1. Log in with your university login.\n"
                   "2. Cannot see a course? Ask your teacher for the enrolment key.\n"
                   "3. Page not loading? Try another browser or clear the cache."),
    },
    "portal": {
        "keywords": ["progres", "portail", "portal", "inscription", "notes", "releve",
                     "grades", "البوابة", "التسجيل", "النقاط"],
        "answer": ("Student portal (PROGRES):\n"
                   "1. Log in with your student number and password.\n"
                   "2. Registration or grades missing? Contact your department secretary.\n"
                   "3. Login refused? See 'forgot password'."),
    },
    "printer": {
        "keywords": ["printer", "print", "imprimante", "imprimer", "impression",
                     "طابعة", "طباعة"],
        "answer": ("Printing problem:\n"
                   "1. Check that the printer is on and has paper.\n"
                   "2. Make sure you chose the right printer in the print window.\n"
                   "3. Still stuck? Tell the lab technician the printer name."),
    },
    "software": {
        "keywords": ["software", "logiciel", "install", "installer", "licence", "license",
                     "java", "eclipse", "office", "برنامج", "تثبيت"],
        "answer": ("Software / installation:\n"
                   "1. Student software (Office, IDEs) is often free with your university account.\n"
                   "2. In the lab computers you cannot install: ask the technician.\n"
                   "3. Tell me the software name and I will try to help."),
    },
}

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

        if user in ["exit", "quit", "bye"]:
            state["awaiting_resolution"] = False
            return "Goodbye!"

        # Handle yes/no answers to the previous troubleshooting advice.
        if state["awaiting_resolution"]:
            if user in YES:
                state["awaiting_resolution"] = False
                return "Great! Glad I could help."

            if user in NO:
                state["awaiting_resolution"] = False
                return "Please contact our IT office:\n" + CONTACT

            # If neither yes nor no, process it as a new question.
            state["awaiting_resolution"] = False

        # Find an IT problem.
        for category, data in RULES.items():
            if any(
                matches_keyword(user, word)
                for word in data["keywords"]
            ):
                state["awaiting_resolution"] = True
                return (
                    data["answer"]
                    + "\n\nIs your problem solved? Reply yes or no."
                )

        # Find small talk: greeting, thanks, or help.
        for category, data in SMALL_TALK.items():
            if any(
                matches_keyword(user, word)
                for word in data["keywords"]
            ):
                return data["answer"]

        # Save unrecognized messages for later review.
        with open(UNMATCHED_FILE, "a", encoding="utf-8") as file:
            file.write(user + "\n")

        return "Sorry, I couldn't identify your problem. Please describe it differently."


@app.post("/chat")
def chat(request: ChatRequest):
    return {
        "reply": get_response(
            request.message,
            request.session_id
        )
    }
