"""
Play Store Search and Auto-Installer for J.A.R.V.I.S. Mobile.
Enables Jarvis to search and install Android applications hands-free.
"""
import time
import urllib.parse

class PlayStoreInstaller:
    def __init__(self, controller, perception):
        self.ac = controller
        self.perception = perception

    def search_and_install_app(self, app_name: str) -> dict:
        """
        Opens Google Play Store search for the given app and attempts to click 'Install'.
        """
        encoded_query = urllib.parse.quote(app_name)
        deep_link = f"market://search?q={encoded_query}"
        self.ac.shell(f"am start -a android.intent.action.VIEW -d '{deep_link}'")
        time.sleep(3.0)

        # Look for 'Install' button in Play Store XML
        coords = self.perception.find_element_center("Install")
        if coords:
            self.ac.tap(coords[0], coords[1])
            return {
                "success": True,
                "message": f"Play Store par '{app_name}' search karke 'Install' par click kar diya hai Sir."
            }
        else:
            return {
                "success": True,
                "message": f"Play Store par '{app_name}' search kar diya hai Sir, kripya target version select karein."
            }
