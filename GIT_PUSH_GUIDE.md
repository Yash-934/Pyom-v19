# GitHub par Code aur APK Kaise Push Kare



---

## Method 0: Naya Project GitHub par Kaise Daalein (Shuru se)

Agar aapke paas ek project hai jo aapke computer par hai, lekin GitHub par nahi hai, to use shuru se GitHub par daalne ke liye ye steps follow karein.

### Step 1: GitHub par ek nayi (khaali) Repository Banayein

1.  Apne GitHub account me login karein.
2.  Upar-right corner me `+` icon par click karein aur `New repository` chunein.
3.  Repository ko ek naam dein (jaise `My-Awesome-App`).
4.  Use `Public` ya `Private` set karein.
5.  **Important:** `Add a README file`, `Add .gitignore`, aur `Choose a license` ko **un-check** (khaali) chhod dein.
6.  `Create repository` par click karein.

Ab aapko ek page dikhega jisme kuch commands honge. Wahan se aapko HTTPS URL copy karna hai. Wo kuch aisa dikhega:
`https://github.com/YourUsername/Your-Repo-Name.git`

### Step 2: Apne Local Project me Git Shuru Karein

Apne project ke root folder me terminal open karein aur ye commands chalayein:

```bash
# Folder ko ek Git repository banata hai
git init

# Sabhi files ko pehli baar commit ke liye add karta hai
git add .

# Pehla commit banata hai
git commit -m "Initial commit"
```

### Step 3: Local Repository ko GitHub se Jodein

Ab aapko apne local project ko batana hai ki use code kahan bhejna hai. Iske liye `git remote add` command ka istemal hota hai.

```bash
# GitHub se copy kiya hua URL yahan paste karein
git remote add origin https://github.com/YourUsername/Your-Repo-Name.git
```

### Step 4: Apne Code ko GitHub par Push Karein

Aakhri step me, apne local code ko GitHub par push karein. `-u` flag pehli baar ke liye zaroori hai, taaki aapka local `main` branch, remote `main` branch se jud jaye.

```bash
git push -u origin main
```

Iske baad, aapka poora project GitHub par upload ho jayega!

---

Is guide me aapko samjhaya jayega ki aap apne code aur `app-release.apk` file ko GitHub par kaise push kar sakte hain.

## 1. Normal Code Push Karna

Jab bhi aap apne code me koi badlav karte hain (jaise ki `lib/main.dart` me kuch change karna), to use GitHub par push karne ke liye ye standard commands hain:

### Step 1: Badlav ko Staging me Add Kare

Sabse pehle, aapko Git ko batana hota hai ki aap kaun si files ke badlav ko commit karna chahte hain. Aap ek-ek file ka naam de sakte hain ya `.` istemal karke sabhi modified files ko add kar sakte hain.

```bash
git add .
```

### Step 2: Badlav ko Commit Kare

Ab in badlavon ko ek message ke saath commit karein. Message me yah likhna accha rehta hai ki aapne kya changes kiye hain.

```bash
git commit -m "Aapke badlav ka chota sa description"
```
*Jaise: `git commit -m "User login screen banaya"`*

### Step 3: Commits ko GitHub par Push Kare

Aakhri step me, apne local commits ko GitHub server par bhej de.

```bash
git push
```

In teen commands se aapka code aapki GitHub repository me update ho jayega.

---

## 2. APK File Push Karna (Special Case)

`app-release.apk` jaisi badi files ko Git repository me push karna normal practice nahi hai, lekin agar aapko yah karna zaroori hai, to niche diye gaye tarike se kar sakte hain.

### Samasya: `.gitignore` file

Aapke project me ek `.gitignore` naam ki file hai. Is file me un sabhi files aur folders ke naam likhe hote hain jinhe Git ko ignore karna chahiye. `build` folder bhi is list me shamil hota hai, kyunki isme temporary aur badi-badi generated files hoti hain.

Isi vajah se, jab aap normal `git add` command se APK ko add karne ki koshish karte hain, to Git use ignore kar deta hai aur ek error dikhata hai.

### Samadhan: Force Add (`-f` flag)

Is samasya ko bypass karne ke liye, hum `git add` command ke saath `-f` (force) flag ka istemal kar sakte hain. Yah Git ko majboor karta hai ki woh `.gitignore` me likhi hui file ko bhi add kar le.

### Step 1: APK File ko Force-Add Kare

Apni `app-release.apk` file ko force ke saath staging me add karein.

```bash
git add -f build/app/outputs/flutter-apk/app-release.apk
```

### Step 2: APK ko Commit Kare

APK file ke liye ek alag commit banayein.

```bash
git commit -m "Release APK add kiya"
```

### Step 3: Commit ko Push Kare

Aakhri me, is commit ko GitHub par push kar de.

```bash
git push
```

**Zaroori Suchna (Important Note):** Badi files (jaise APK, video, zip) ko Git me baar-baar push karne se aapki repository ka size bahut zyada badh sakta hai aur use clone karne me bahut time lag sakta hai. Yah tarika sirf zaroorat padne par hi istemal karein. Behtar tarika `Git LFS` (Large File Storage) ya fir APK ko release assets ke taur par GitHub par upload karna hota hai.


---

## Method 3: Version Release Banana (Recommended for APK)

Yahi APK ya kisi bhi software ko release karne ka sabse professional aur sahi tareeka hai. Isse aapki Git history saaf rehti hai aur koi bhi user aasani se alag-alag version download kar sakta hai.

Iske liye hum **Git Tags** aur **GitHub Releases** ka istemal karte hain.

### Step 0: GitHub CLI me Login Karna (Sirf ek baar)
Release banane ke liye hum `gh` (GitHub CLI) tool ka istemal karte hain. Ise pehli baar istemal karne se pehle aapko login karna hoga.

```bash
gh auth login
```

Is command ko chalane ke baad, aapse kuch sawal pooche jayenge:
1.  `What account do you want to log into?` -> `GitHub.com` chunein.
2.  `What is your preferred protocol for Git operations?` -> `HTTPS` chunein.
3.  `Authenticate Git with your GitHub credentials?` -> `Y` (Yes) chunein.
4.  `How would you like to authenticate?` -> `Login with a web browser` chunein.

Iske baad, aapko ek one-time code milega aur ek link open hogi. Us code ko browser me paste karke authentication poora karein.

### Step 1: Naya Git Tag Banana
Pehle, hum apne code ke current state ko ek version number (jaise `v1.0`) se "tag" karenge. Yah aapke project ka ek permanent snapshot bana deta hai.

```bash
git tag -a v1.0 -m "Version 1.0: Initial Release"
```
*Aap `v1.0` ki jagah `v1.1` ya koi aur naya version number de sakte hain. Message me us version ke baare me likhein.*

### Step 2: Tag ko GitHub par Push Karna
Ab is tag ko aapki GitHub repository me push karna hoga.
```bash
git push origin v1.0
```
*(Yahan bhi `v1.0` ki jagah aapka naya tag name aayega)*

### Step 3: GitHub Release Banana aur APK Upload Karna
Login aur Tag push hone ke baad, aap is command se ek naya release bana sakte hain aur apni `app-release.apk` file uske saath "Asset" ke roop me jod sakte hain.

```bash
gh release create v1.0 build/app/outputs/flutter-apk/app-release.apk -t "Version 1.0" -n "Is release me kya khaas hai, uska description yahan likhein."
```

*   `v1.0`: Aapka tag name.
*   `build/app/outputs/flutter-apk/app-release.apk`: Aapki APK file ka path.
*   `-t`: Release ka Title.
*   `-n`: Release ke notes ya description.

Is command ke chalne ke baad, aapko ek link milega. Us link par jaakar koi bhi `app-release.apk` ko **Assets** section se download kar sakta hai.

